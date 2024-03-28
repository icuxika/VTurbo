package com.icuxika.vturbo.client.server

import com.icuxika.vturbo.client.protocol.ProtocolHandle
import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.extensions.toSpeed
import com.icuxika.vturbo.commons.tcp.ByteArrayEvent
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.SleepingWaitStrategy
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import com.lmax.disruptor.util.DaemonThreadFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.scheduleAtFixedRate

/**
 * 管理与代理服务器之间的Socket链接，为app与目标服务器之间的请求数据进行转发
 */
class ProxyServerManager(private val proxyServerAddress: String) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default + CoroutineName("ProxyServerManager"))

    /**
     * 代理服务器Socket
     */
    private var proxyServerSocket: Socket = Socket()

    /**
     * appid <-> app socket
     */
    private val protocolHandleMap = ConcurrentHashMap<Int, ProtocolHandle>()

    /**
     * 接收app的请求数据然后转发给代理服务端
     */
    private val disruptor =
        Disruptor({ ByteArrayEvent() }, 1024, DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, SleepingWaitStrategy())

    /**
     * 代理服务器->代理客户端 流量统计
     */
    private val bytesInChannel = Channel<Int>(Channel.UNLIMITED)

    /**
     * 代理客户端->代理服务器 流量统计
     */
    private val bytesOutChannel = Channel<Int>(Channel.UNLIMITED)

    init {
        // 创建计算网络传输速度的定时任务
        createSpeedCalculateTask()
        // 开启线程不断从队列中读取数据转发到代理服务端
        startForwardRequestToProxyServerTask()

        runCatching {
            val (proxyServerHostname, proxyServerPort) = proxyServerAddress.split(":")
            LOGGER.info("代理服务器地址->$proxyServerHostname:$proxyServerPort")
            proxyServerSocket.connect(InetSocketAddress(proxyServerHostname, proxyServerPort.toInt()))
            LOGGER.info("与代理服务器建立连接成功")

            scope.launch {
                runCatching {
                    while (true) {
                        proxyServerSocket.getInputStream().readCompletePacket(LOGGER).let { packet ->
                            scope.launch { bytesInChannel.send(packet.length) }
                            handleResponse(packet)
                        }
                    }
                }.onFailure {
                    LOGGER.error(
                        "等待读取代理服务端的数据时捕获到异常->[${it.message}]，请检查代理服务器是否还在正常运行",
                        it
                    )
                    proxyServerSocket.close()
                    protocolHandleMap.values.forEach { abstractProtocolHandle -> abstractProtocolHandle.shutdownAbnormally() }
                }
            }
        }.onFailure {
            LOGGER.error("无法与代理服务器建立连接", it)
            proxyServerSocket.close()
        }
    }

    private suspend fun handleResponse(packet: Packet) {
        val appId = packet.appId
        val instructionId = packet.instructionId
        val length = packet.length
        val data = packet.data
        when (instructionId) {
            ProxyInstruction.CONNECT.instructionId -> {
                protocolHandleMap[appId]?.afterHandshake()
            }

            ProxyInstruction.SEND.instructionId -> {
                protocolHandleMap[appId]?.forwardRequestToChannelOfApp(data)
            }

            ProxyInstruction.DISCONNECT.instructionId -> {
                LOGGER.info("收到代理服务端目标服务器请求结束的信号")
                protocolHandleMap[appId]?.shutdownGracefully()
            }

            ProxyInstruction.EXCEPTION_DISCONNECT.instructionId -> {
                protocolHandleMap[appId]?.shutdownAbnormally()
            }

            else -> {}
        }
    }

    /**
     * 转发目标服务器的请求数据到代理客户端
     */
    fun forwardRequestToProxyServer(data: ByteArray) {
        runCatching {
            disruptor.ringBuffer.publishEvent { event, _ -> event.value = data }
        }.onFailure {
            LOGGER.error("向代理服务器的disruptor写入数据时发生错误[${it.message}]", it)
            disruptor.shutdown()
        }
    }

    /**
     * 创建一个线程不断读取队列中的请求数据然后转发给代理服务端
     */
    private fun startForwardRequestToProxyServerTask() {
        disruptor.handleEventsWith(object : EventHandler<ByteArrayEvent> {
            override fun onEvent(event: ByteArrayEvent?, sequence: Long, endOfBatch: Boolean) {
                event?.let { e ->
                    runCatching {
                        val data = e.value
                        scope.launch { bytesOutChannel.send(data.size) }
                        proxyServerSocket.getOutputStream().write(data)
                    }.onFailure {
                        LOGGER.error("向代理服务端转发数据遇到了错误[${it.message}]", it)
                        disruptor.shutdown()
                    }
                }
            }
        })
        disruptor.start()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun createSpeedCalculateTask() {
        // 下载速度定时器
        Timer().scheduleAtFixedRate(10000, 60000) {
            runBlocking {
                var allBytesIn = 0
                while (!bytesInChannel.isEmpty) {
                    allBytesIn += bytesInChannel.receive()
                }
                // B / s
                val transferSpeedInBytesPerSec = allBytesIn / (60000.0 / 1000.0)
                LOGGER.debug("下载速度为->${transferSpeedInBytesPerSec.toSpeed()}")
            }
        }
        // 上传速度定时器
        Timer().scheduleAtFixedRate(10000, 60000) {
            runBlocking {
                var allBytesOut = 0
                while (!bytesOutChannel.isEmpty) {
                    allBytesOut += bytesOutChannel.receive()
                }
                // B / s
                val transferSpeedInBytesPerSec = allBytesOut / (60000.0 / 1000.0)
                LOGGER.debug("上传速度为->${transferSpeedInBytesPerSec.toSpeed()}")
            }
        }
    }

    /**
     * 确认app与要访问的目标服务器建立起链接后，注册到[ProxyServerManager.protocolHandleMap]中
     */
    fun registerProtocolHandle(protocolHandle: ProtocolHandle) {
        protocolHandleMap[protocolHandle.getId()] = protocolHandle
    }

    /**
     * 从[ProxyServerManager.protocolHandleMap]中移除app
     */
    fun unregisterProtocolHandle(protocolHandle: ProtocolHandle) {
        protocolHandleMap.remove(protocolHandle.getId())
    }

    companion object {
        val LOGGER = logger()
    }
}