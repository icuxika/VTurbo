package com.icuxika.vturbo.client.server

import com.icuxika.vturbo.client.protocol.ProtocolHandle
import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.extensions.toSpeed
import com.icuxika.vturbo.commons.tcp.ByteArrayEvent
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.ExceptionHandler
import com.lmax.disruptor.SleepingWaitStrategy
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import com.lmax.disruptor.util.DaemonThreadFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
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
    private val scope = CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("ProxyServerManager"))

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
        // 配置并启动Disruptor用于转发app的请求数据到代理服务端
        startForwardRequestToProxyServerDisruptor()

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
                            handlePacketFromProxyServer(packet)
                        }
                    }
                }.onFailure {
                    LOGGER.error(
                        "读取代理服务端的数据时捕获到异常->[${it.message}]，请检查代理服务器是否还在正常运行",
                        it
                    )
                    proxyServerSocket.close()
                    protocolHandleMap.values.forEach { protocolHandle -> protocolHandle.shutdownAbnormally() }
                    disruptor.shutdown()
                }
            }
        }.onFailure {
            LOGGER.error("无法与代理服务器建立连接", it)
            proxyServerSocket.close()
        }
    }

    /**
     * 处理代理服务端转发过来的请求数据
     */
    private suspend fun handlePacketFromProxyServer(packet: Packet) {
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
        disruptor.ringBuffer.publishEvent { event, _ -> event.value = data }
    }

    /**
     * 配置并启动Disruptor用于转发app的请求数据到代理服务端
     */
    private fun startForwardRequestToProxyServerDisruptor() {
        val eventHandler =
            EventHandler<ByteArrayEvent> { event, _, _ ->
                event?.let {
                    val data = it.value
                    scope.launch { bytesOutChannel.send(data.size) }
                    proxyServerSocket.getOutputStream().write(data)
                }
            }
        disruptor.handleEventsWith(eventHandler)
        disruptor.handleExceptionsFor(eventHandler).with(object : ExceptionHandler<ByteArrayEvent> {
            override fun handleEventException(ex: Throwable?, sequence: Long, event: ByteArrayEvent?) {
                ex?.let {
                    LOGGER.error("Disruptor处理事件时遇到错误[${it.message}]", it)
                    if (it is IOException) {
                        // 向代理服务端写入数据时如果发生了的异常，读取线程也会报错并关闭所有连接，然后shutdown Disruptor，此处异常应该很难看到触发
                        throw RuntimeException(ex)
                    }
                }
            }

            override fun handleOnStartException(ex: Throwable?) {
                ex?.let {
                    LOGGER.error("Disruptor启动时遇到错误[${it.message}]", it)
                }
            }

            override fun handleOnShutdownException(ex: Throwable?) {
                ex?.let {
                    LOGGER.error("Disruptor停止时遇到错误[${it.message}]", it)
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