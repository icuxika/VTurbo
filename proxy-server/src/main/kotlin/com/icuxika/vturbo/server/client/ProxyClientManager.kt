package com.icuxika.vturbo.server.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import com.icuxika.vturbo.commons.tcp.toByteArray
import com.icuxika.vturbo.server.server.TargetServerManager
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * 一个[ProxyClientManager]实例对应一个代理客户端连接，共用一个[TargetServerManager]
 */
class ProxyClientManager(
    private val targetServerManager: TargetServerManager,
    private val client: Socket,
    val clientId: Int
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("client:$clientId"))

    /**
     * 接收目标服务器的请求数据然后转发给代理客户端
     */
    private val queueToProxyClient = ArrayBlockingQueue<ByteArray>(20 * 1024 * 1024)

    /**
     * 接收代理客户端的请求数据然后转发给目标服务器
     */
    private val queueToTargetServer = ConcurrentLinkedQueue<Packet>()

    fun startRequestProxy() {
        LOGGER.info("新客户端建立连接，id->$clientId")
        targetServerManager.registerClientManager(this)
        // 开启线程不断从队列中读取数据转发到代理客户端
        startForwardRequestToProxyClientTask()
        // 开启线程不断从队列中读取数据转发到目标服务器
        startForwardRequestToTargetServerTask()

        thread {
            runCatching {
                while (true) {
                    client.getInputStream().readCompletePacket(LOGGER).let { packet ->
                        queueToTargetServer.offer(packet)
                    }
                }
            }.onFailure {
                LOGGER.error("从客户端[$clientId]读取数据时出现了错误[${it.message}]")
                cleanProxyClientSocket()
            }
        }
    }

    /**
     * 转发目标服务器的请求数据到代理客户端
     */
    fun forwardRequestToProxyClient(data: ByteArray) {
        queueToProxyClient.put(data)
    }

    /**
     * 创建一个线程不断读取队列中的请求数据然后转发给代理客户端
     */
    private fun startForwardRequestToProxyClientTask() {
        thread {
            runCatching {
                while (true) {
                    queueToProxyClient.take().let {
                        client.getOutputStream().write(it)
                    }
                }
            }.onFailure {
                LOGGER.error("向客户端[$clientId]转发数据遇到了错误[${it.message}]")
                cleanProxyClientSocket()
            }
        }
    }

    /**
     * 不断读取队列中请求数据转发给目标服务器
     */
    private fun startForwardRequestToTargetServerTask() {
        scope.launch {
            runCatching {
                while (true) {
                    queueToTargetServer.poll()?.let { packet ->
                        val (appId, instructionId, length, data) = packet
                        when (instructionId) {
                            ProxyInstruction.CONNECT.instructionId -> {
                                runCatching {
                                    // 读取目标服务器地址和端口，Socks 5协议传递的端口占用两个字节
                                    val remoteAddressBytes = data.sliceArray(0 until length - 2)
                                    val remotePortBytes = data.sliceArray(length - 2 until length)
                                    val remoteAddress = InetAddress.getByName(String(remoteAddressBytes))
                                    val remotePort = ByteBuffer.wrap(remotePortBytes).getShort()

                                    targetServerManager.connectToServer(clientId, appId, remoteAddress, remotePort)
                                }.onFailure {
                                    LOGGER.error("app将与目标服务器建立连接时遇到一个错误[${it.message}]")
                                    // 通知代理客户端app
                                    forwardRequestToProxyClient(
                                        Packet(
                                            appId,
                                            ProxyInstruction.EXCEPTION_DISCONNECT.instructionId,
                                            0,
                                            byteArrayOf()
                                        ).toByteArray()
                                    )
                                }
                            }

                            ProxyInstruction.SEND.instructionId -> {
                                targetServerManager.forwardRequestToServer(clientId, appId, data)
                            }

                            ProxyInstruction.DISCONNECT.instructionId -> {
                                targetServerManager.closeSocketChannel(clientId, appId)
                            }

                            ProxyInstruction.EXCEPTION_DISCONNECT.instructionId -> {
                                targetServerManager.closeSocketChannel(clientId, appId)
                            }

                            else -> {}
                        }
                    }
                }
            }
        }
    }

    /**
     * 与代理客户端之间的连接断开处理
     */
    private fun cleanProxyClientSocket() {
        runCatching {
            client.close()
        }
        targetServerManager.closeAllChannelByClientId(clientId)
        targetServerManager.unregisterClientManager(this)
    }

    companion object {
        val LOGGER = logger()
    }
}