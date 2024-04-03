package com.icuxika.vturbo.client.server

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.extensions.toSpeed
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

/**
 * 管理与代理服务器之间的Socket链接，为app与目标服务器之间的请求数据进行转发
 */
class ProxyServerManager(proxyServerAddress: String) : AbstractProxyServer(proxyServerAddress) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("ProxyServerManager"))

    /**
     * 代理服务器Socket
     */
    private var proxyServerSocket: Socket = Socket()

    /**
     * 代理服务器->代理客户端 流量统计
     */
    private val bytesInChannel = Channel<Int>(Channel.UNLIMITED)
    private lateinit var bytesInSpeedCalculateTask: TimerTask

    /**
     * 代理客户端->代理服务器 流量统计
     */
    private val bytesOutChannel = Channel<Int>(Channel.UNLIMITED)
    private lateinit var bytesOutSpeedCalculateTask: TimerTask

    init {
        initProxyServer()
        // 创建计算网络传输速度的定时任务
        startSpeedCalculateTask()
    }

    override fun initProxyServerImpl(inetSocketAddress: InetSocketAddress) {
        runCatching {
            proxyServerSocket.connect(inetSocketAddress)
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
                    stopSpeedCalculateTask()
                    shutdownDisruptor()
                    // 关闭所有已经关联的app连接
                    protocolHandleMap.values.forEach { protocolHandle -> protocolHandle.shutdownAbnormally() }
                }
            }
        }.onFailure {
            LOGGER.error("无法与代理服务器建立连接", it)
            proxyServerSocket.close()
            stopSpeedCalculateTask()
            shutdownDisruptor()
            throw RuntimeException(it)
        }
    }

    /**
     * 处理代理服务端转发过来的请求数据
     */
    private suspend fun handlePacketFromProxyServer(packet: Packet) {
        val (appId, instructionId, _, data) = packet
        when (instructionId) {
            ProxyInstruction.CONNECT.instructionId -> {
                protocolHandleMap[appId]?.targetServerCanBeConnectedCallback()
            }

            ProxyInstruction.SEND.instructionId -> {
                protocolHandleMap[appId]?.forwardRequestToChannelOfApp(data)
            }

            ProxyInstruction.DISCONNECT.instructionId -> {
                LOGGER.info("[$appId]收到代理服务端目标服务器请求结束的信号")
                protocolHandleMap[appId]?.shutdownGracefully()
            }

            ProxyInstruction.EXCEPTION_DISCONNECT.instructionId -> {
                protocolHandleMap[appId]?.shutdownAbnormally()
            }

            else -> {}
        }
    }

    /**
     * 将请求数据转发给代理服务端
     */
    override fun forwardRequestToProxyServerImpl(data: ByteArray) {
        scope.launch { bytesOutChannel.send(data.size) }
        proxyServerSocket.getOutputStream().write(data)
    }

    /**
     * 开始计算网络传输速度
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startSpeedCalculateTask() {
        LOGGER.info("开始计算网络传输速度")
        // 下载速度定时器
        bytesInSpeedCalculateTask = Timer().scheduleAtFixedRate(10000, 60000) {
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
        bytesOutSpeedCalculateTask = Timer().scheduleAtFixedRate(10000, 60000) {
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
     * 停止计算网络传输速度的定时任务
     */
    private fun stopSpeedCalculateTask() {
        LOGGER.info("停止计算网络传输速度")
        bytesInSpeedCalculateTask.cancel()
        bytesOutSpeedCalculateTask.cancel()
    }

    companion object {
        val LOGGER = logger()
    }
}