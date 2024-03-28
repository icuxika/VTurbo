package com.icuxika.vturbo.server.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.*
import com.icuxika.vturbo.server.server.ClientPacket
import com.icuxika.vturbo.server.server.TargetServerManager
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.SleepingWaitStrategy
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import com.lmax.disruptor.util.DaemonThreadFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
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
    private val scope = CoroutineScope(supervisor + Dispatchers.Default + CoroutineName("ProxyClientManager$clientId"))

    /**
     * 接收目标服务器的请求数据然后转发给代理客户端
     */
    private val disruptor =
        Disruptor({ ByteArrayEvent() }, 1024, DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, SleepingWaitStrategy())

    fun startRequestProxy() {
        LOGGER.info("新客户端建立连接，id->$clientId")
        // 注册到 TargetServerManager 以接收目标服务器响应的请求数据
        targetServerManager.registerClientManager(this)
        // 开启线程不断从队列中读取数据转发到代理客户端
        disruptor.handleEventsWith(object : EventHandler<ByteArrayEvent> {
            override fun onEvent(event: ByteArrayEvent?, sequence: Long, endOfBatch: Boolean) {
                event?.let { e ->
                    runCatching {
                        client.getOutputStream().write(e.value)
                    }.onFailure {
                        LOGGER.error("向客户端[$clientId]转发数据遇到了错误[${it.message}]", it)
                        cleanProxyClientSocket()
                        disruptor.shutdown()
                    }
                }
            }
        })
        disruptor.start()

        // 主要线程，不断读取客户端的请求数据并处理
        thread {
            runCatching {
                while (true) {
                    client.getInputStream().readCompletePacket(LOGGER).let { packet ->
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
                                    LOGGER.error("app将与目标服务器建立连接时遇到一个错误[${it.message}]", it)
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
                                targetServerManager.forwardRequestToTargetServer(ClientPacket(clientId, appId, data))
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
            }.onFailure {
                LOGGER.error("从客户端[$clientId]读取数据时出现了错误[${it.message}]", it)
                cleanProxyClientSocket()
            }
        }
    }

    /**
     * 转发目标服务器的请求数据到代理客户端
     */
    fun forwardRequestToProxyClient(data: ByteArray) {
        runCatching {
            disruptor.ringBuffer.publishEvent { event, _ -> event.value = data }
        }.onFailure {
            LOGGER.error("向[$clientId]disruptor写入数据时遇到错误[${it.message}]", it)
            disruptor.shutdown()
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
        disruptor.shutdown()
    }

    companion object {
        val LOGGER = logger()
    }
}