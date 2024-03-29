package com.icuxika.vturbo.server.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.*
import com.icuxika.vturbo.server.server.ClientPacket
import com.icuxika.vturbo.server.server.TargetServerManager
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.ExceptionHandler
import com.lmax.disruptor.SleepingWaitStrategy
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import com.lmax.disruptor.util.DaemonThreadFactory
import java.io.IOException
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
    /**
     * 接收目标服务器的请求数据然后转发给代理客户端
     */
    private val disruptor =
        Disruptor({ ByteArrayEvent() }, 1024, DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, SleepingWaitStrategy())

    fun startRequestProxy() {
        LOGGER.info("新客户端建立连接，id->$clientId")
        // 注册到 TargetServerManager 以接收目标服务器响应的请求数据
        targetServerManager.registerClientManager(this)
        // 配置并启动Disruptor用于转发目标服务器的请求数据到代理客户端
        startForwardRequestToProxyClientDisruptor()

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
     * 配置并启动Disruptor用于转发目标服务器的请求数据到代理客户端
     */
    private fun startForwardRequestToProxyClientDisruptor() {
        val eventHandler =
            EventHandler<ByteArrayEvent> { event, _, _ ->
                event?.let {
                    client.getOutputStream().write(it.value)
                }
            }
        disruptor.handleEventsWith(eventHandler)
        disruptor.handleExceptionsFor(eventHandler).with(object : ExceptionHandler<ByteArrayEvent> {
            override fun handleEventException(ex: Throwable?, sequence: Long, event: ByteArrayEvent?) {
                ex?.let {
                    LOGGER.error("[$clientId]Disruptor处理事件时遇到错误[${it.message}]", it)
                    if (it is IOException) {
                        // 向代理客户端写入数据时如果发生了的异常，读取线程也会报错并关闭所有连接，然后shutdown Disruptor，此处异常应该很难看到触发
                        throw RuntimeException(ex)
                    }
                }
            }

            override fun handleOnStartException(ex: Throwable?) {
                ex?.let {
                    LOGGER.error("[$clientId]Disruptor启动时遇到错误[${it.message}]", it)
                }
            }

            override fun handleOnShutdownException(ex: Throwable?) {
                ex?.let {
                    LOGGER.error("[$clientId]Disruptor停止时遇到错误[${it.message}]", it)
                }
            }
        })
        disruptor.start()
    }

    /**
     * 转发目标服务器的请求数据到代理客户端
     */
    fun forwardRequestToProxyClient(data: ByteArray) {
        disruptor.ringBuffer.publishEvent { event, _ -> event.value = data }
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