package com.icuxika.vturbo.server.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class ProxyClientManager(private val client: Socket, private val clientId: Int) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("client:$clientId"))

    /**
     * 接收目标服务器的请求数据然后转发给客户端
     */
    private val queue = ConcurrentLinkedQueue<ByteArray>()

    /**
     * clientId:appId -> appRequest
     */
    private val appRequestContextHolderMap = ConcurrentHashMap<String, AppRequestContextHolder>()
    private val key: (x: Int, y: Int) -> String = { x, y -> "$x:$y" }

    fun startRequestProxy() {
        LOGGER.info("新客户端建立连接，id->$clientId")
        // 开启线程不断从队列中读取数据转发到代理客户端
        startForwardRequestToProxyClientTask()

        thread {
            runCatching {
                while (true) {
                    // TODO 处理不是由客户端发来的请求数据
                    client.getInputStream().readCompletePacket(LOGGER).let { packet ->
                        val appId = packet.appId
                        val instructionId = packet.instructionId
                        val length = packet.length
                        val data = packet.data
                        when (instructionId) {
                            ProxyInstruction.CONNECT.instructionId -> {
                                runCatching {
                                    // 读取目标服务器地址和端口，Socks 5协议传递的端口占用两个字节
                                    val remoteAddressBytes = data.sliceArray(0 until length - 2)
                                    val remotePortBytes = data.sliceArray(length - 2 until length)
                                    val remoteAddress = InetAddress.getByName(String(remoteAddressBytes))
                                    val remotePort = ByteBuffer.wrap(remotePortBytes).getShort()

                                    AppRequestContextHolder(
                                        this@ProxyClientManager,
                                        scope,
                                        clientId,
                                        appId,
                                        remoteAddress,
                                        remotePort
                                    ).startRequestProxy()
                                }.onFailure {
                                    LOGGER.error("app将与目标服务器建立连接时遇到一个错误[${it.message}]")
                                }
                            }

                            ProxyInstruction.SEND.instructionId -> {
                                appRequestContextHolderMap[key(clientId, appId)]?.forwardRequestToRemoteSocket(data)
                            }

                            ProxyInstruction.RESPONSE.instructionId -> {}
                            ProxyInstruction.DISCONNECT.instructionId -> {
                                appRequestContextHolderMap[key(clientId, appId)]?.closeRemoteSocket()
                            }

                            else -> {
                                LOGGER.warn("不支持的指令类型[$instructionId]")
                            }
                        }
                    }
                }
            }.onFailure {
                LOGGER.error("从客户端[$clientId]读取数据时出现了错误[${it.message}]")
                clean()
            }
        }
    }

    fun registerAppRequest(appRequestContextHolder: AppRequestContextHolder) {
        appRequestContextHolderMap[key(clientId, appRequestContextHolder.appId)] = appRequestContextHolder
    }

    fun unregisterAppRequest(appRequestContextHolder: AppRequestContextHolder) {
        appRequestContextHolderMap.remove(key(clientId, appRequestContextHolder.appId))
    }

    /**
     * 转发目标服务器的请求数据到代理客户端
     */
    fun forwardRequestToProxyClient(data: ByteArray) {
        queue.offer(data)
    }

    /**
     * 创建一个线程不断读取队列中的请求数据然后转发给代理客户端
     */
    private fun startForwardRequestToProxyClientTask() {
        thread {
            runCatching {
                while (true) {
                    val data = queue.poll() ?: continue
                    client.getOutputStream().write(data)
                }
            }.onFailure {
                LOGGER.error("向客户端[$clientId]转发数据遇到了错误[${it.message}]")
                clean()
            }
        }
    }

    /**
     * 与代理客户端之间的连接断开处理
     */
    private fun clean() {
        runCatching {
            client.close()
        }
        runCatching {
            appRequestContextHolderMap.values.forEach { appRequestContextHolder ->
                appRequestContextHolder.closeRemoteSocket()
            }
        }
    }

    companion object {
        val LOGGER = logger()
    }
}