package com.icuxika.vturbo.client.server

import com.icuxika.vturbo.client.protocol.NAbstractProtocolHandle
import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.extensions.toSpeed
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.concurrent.thread

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
    private val nProtocolHandle = ConcurrentHashMap<Int, NAbstractProtocolHandle>()

    /**
     * 接收app的请求数据然后转发给代理服务端
     */
    private val queue = ConcurrentLinkedQueue<ByteArray>()

    /**
     * 代理服务器->代理客户端 流量统计
     */
    private val bytesInChannel = Channel<Int>(Channel.UNLIMITED)

    /**
     * 代理客户端->代理服务器 流量统计
     */
    private val bytesOutChannel = Channel<Int>(Channel.UNLIMITED)

    init {
        // 开启线程不断从队列中读取数据转发到代理服务端
        startForwardRequestToProxyClientTask()

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

            CoroutineScope(Executors.newFixedThreadPool(4).asCoroutineDispatcher()).launch {
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
                                        nProtocolHandle[appId]?.afterHandshake()
                                    }

                                    ProxyInstruction.SEND.instructionId -> {
                                        nProtocolHandle[appId]?.forwardRequestToChannelOfApp(data)
                                        bytesInChannel.send(data.size)
                                    }

                                    ProxyInstruction.RESPONSE.instructionId -> {}
                                    ProxyInstruction.DISCONNECT.instructionId -> {
                                        nProtocolHandle[appId]?.clean()
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }
                }.onFailure {
                    LOGGER.error("等待读取代理服务端的数据时捕获到异常->[${it.message}]，请检查代理服务器是否还在正常运行")
                    proxyServerSocket.close()
                    nProtocolHandle.values.forEach { abstractProtocolHandle -> abstractProtocolHandle.clean() }
                }
            }
        }.onFailure {
            LOGGER.error("无法与代理服务器建立连接")
            proxyServerSocket.close()
        }
    }

    /**
     * 转发目标服务器的请求数据到代理客户端
     */
    fun forwardRequestToProxyServer(data: ByteArray) {
        queue.offer(data)
    }

    /**
     * 创建一个线程不断读取队列中的请求数据然后转发给代理服务端
     */
    private fun startForwardRequestToProxyClientTask() {
        thread {
            runCatching {
                while (true) {
                    val data = queue.poll() ?: continue
                    proxyServerSocket.getOutputStream().write(data)
                }
            }.onFailure {
                LOGGER.error("向代理服务端转发数据遇到了错误[${it.message}]")
            }
        }
    }

    /**
     * 确认app与要访问的目标服务器建立起链接后，注册到[ProxyServerManager.nProtocolHandle]中
     */
    fun registerProtocolHandle(nAbstractProtocolHandle: NAbstractProtocolHandle) {
        nProtocolHandle[nAbstractProtocolHandle.appId] = nAbstractProtocolHandle
    }

    /**
     * 从[ProxyServerManager.nProtocolHandle]中移除app
     */
    fun unregisterProtocolHandle(nAbstractProtocolHandle: NAbstractProtocolHandle) {
        nProtocolHandle.remove(nAbstractProtocolHandle.appId)
    }

    companion object {
        val LOGGER = logger()
    }
}