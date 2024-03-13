package com.icuxika.vturbo.client.server

import com.icuxika.vturbo.client.AppSocketStatus
import com.icuxika.vturbo.client.protocol.AbstractProtocolHandle
import com.icuxika.vturbo.commons.extensions.isConnecting
import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.extensions.toSpeed
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.scheduleAtFixedRate

/**
 * 管理与代理服务器之间的Socket链接，为app与目标服务器之间的请求数据进行转发
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyServerManager(private val proxyServerAddress: String) {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("ProxyServerManager"))

    /**
     * 代理服务器Socket
     */
    private var proxyServerSocket: Socket = Socket()

    /**
     * appid <-> app socket
     */
    private val protocolHandleMap = ConcurrentHashMap<Int, AbstractProtocolHandle>()

    /**
     * lock 同一时间只有一个app的Packet可以写入代理服务器的outputStream
     */
    private val mutex = Mutex()

    /**
     * 代理服务器->代理客户端 流量统计
     */
    private val bytesInChannel = Channel<Int>(Channel.UNLIMITED)

    /**
     * 代理客户端->代理服务器 流量统计
     */
    private val bytesOutChannel = Channel<Int>(Channel.UNLIMITED)

    init {
        runCatching {
            val (proxyServerHostname, proxyServerPort) = proxyServerAddress.split(":")
            LOGGER.info("代理服务器地址->$proxyServerHostname:$proxyServerPort")
            proxyServerSocket.connect(InetSocketAddress(proxyServerHostname, proxyServerPort.toInt()))
            LOGGER.info("与代理服务器建立连接成功")

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

            scope.launch {
                runCatching {
                    proxyServerSocket.use {
                        while (true) {
                            it.getInputStream().readCompletePacket(LOGGER).let { packet ->
                                val appId = packet.appId
                                val instructionId = packet.instructionId
                                val length = packet.length
                                val data = packet.data
                                when (instructionId) {
                                    ProxyInstruction.CONNECT.instructionId -> {
                                        protocolHandleMap[appId]?.afterHandshake()
                                    }

                                    ProxyInstruction.SEND.instructionId -> {
                                        protocolHandleMap[appId]?.forwardRequestToApp(
                                            data,
                                            AppSocketStatus.ON_FORWARDING
                                        )
                                        bytesInChannel.send(data.size)
                                    }

                                    ProxyInstruction.RESPONSE.instructionId -> {}
                                    ProxyInstruction.DISCONNECT.instructionId -> {
                                        protocolHandleMap[appId]?.clean()
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }
                }.onFailure {
                    LOGGER.error("等待读取代理服务端的数据时捕获到异常->[${it.message}]，请检查代理服务器是否还在正常运行")
                    proxyServerSocket.close()
                    protocolHandleMap.values.forEach { abstractProtocolHandle -> abstractProtocolHandle.clean() }
                }
            }
        }.onFailure {
            LOGGER.error("无法与代理服务器建立连接")
            proxyServerSocket.close()
        }
    }

    /**
     * 发送请求数据到代理服务器
     */
    suspend fun sendRequestDataToProxyServer(appId: Int, data: ByteArray): Boolean {
        mutex.withLock {
            if (proxyServerSocket.isConnecting()) {
                proxyServerSocket.getOutputStream().write(data)
                bytesOutChannel.send(data.size)
                return true
            }
            LOGGER.warn("收到app[$appId]发送的请求数据，但是与代理服务器之间的连接已经关闭")
            return false
        }
    }

    /**
     * 确认app与要访问的目标服务器建立起链接后，注册到[ProxyServerManager.protocolHandleMap]中
     */
    fun registerProtocolHandle(abstractProtocolHandle: AbstractProtocolHandle) {
        protocolHandleMap[abstractProtocolHandle.appId] = abstractProtocolHandle
    }

    /**
     * 从[ProxyServerManager.protocolHandleMap]中移除app
     */
    fun unregisterProtocolHandle(abstractProtocolHandle: AbstractProtocolHandle) {
        protocolHandleMap.remove(abstractProtocolHandle.appId)
    }

    companion object {
        val LOGGER = logger()
    }
}