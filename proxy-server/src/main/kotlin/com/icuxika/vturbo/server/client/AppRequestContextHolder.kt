package com.icuxika.vturbo.server.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.IO_READ_BUFFER_SIZE
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class AppRequestContextHolder(
    private val proxyClientManager: ProxyClientManager,
    private val scope: CoroutineScope,
    private val clientId: Int,
    val appId: Int,
    private val remoteAddress: InetAddress,
    private val remotePort: Short,
) {
    /**
     * 目标服务器 Socket
     */
    private val remoteSocket = Socket()
    private lateinit var job: Job

    fun startRequestProxy() {
        job = scope.launch {
            runCatching {
                remoteSocket.connect(InetSocketAddress(remoteAddress, remotePort.toInt()))
                // 通知代理客户端目标服务器能够正常连接
                proxyClientManager.forwardRequestToProxyClient(
                    Packet(
                        appId,
                        ProxyInstruction.CONNECT.instructionId,
                        0,
                        byteArrayOf()
                    ).toByteArray()
                )
                // 在ProxyClientManager注册
                proxyClientManager.registerAppRequest(this@AppRequestContextHolder)
                LOGGER.info("[$clientId:$appId]可以与[$remoteAddress:$remotePort]建立连接，开始请求转发")

                runCatching {
                    val buffer = ByteArray(IO_READ_BUFFER_SIZE)
                    var bytesRead: Int
                    while (remoteSocket.getInputStream().read(buffer).also { bytesRead = it } != -1) {
                        proxyClientManager.forwardRequestToProxyClient(
                            Packet(
                                appId,
                                ProxyInstruction.SEND.instructionId,
                                bytesRead,
                                buffer.sliceArray(0 until bytesRead)
                            ).toByteArray()
                        )
                    }
                }.onFailure {
                    LOGGER.error("[$remoteAddress:$remotePort]---->[$clientId:$appId]发生错误[${it.message}]")
                    proxyClientManager.forwardRequestToProxyClient(
                        Packet(
                            appId,
                            ProxyInstruction.EXCEPTION_DISCONNECT.instructionId,
                            0,
                            byteArrayOf()
                        ).toByteArray()
                    )
                    closeRemoteSocket()
                    return@runCatching
                }
                LOGGER.info("[$remoteAddress:$remotePort]---->[$clientId:$appId]请求结束")
                proxyClientManager.forwardRequestToProxyClient(
                    Packet(
                        appId,
                        ProxyInstruction.DISCONNECT.instructionId,
                        0,
                        byteArrayOf()
                    ).toByteArray()
                )
                closeRemoteSocket()
            }.onFailure {
                LOGGER.warn("[$clientId:$appId]无法与[$remoteAddress:$remotePort]建立连接[${it.message}]")
                // 通知代理客户端目标服务器无法连接
                proxyClientManager.forwardRequestToProxyClient(
                    Packet(
                        appId,
                        ProxyInstruction.EXCEPTION_DISCONNECT.instructionId,
                        0,
                        byteArrayOf()
                    ).toByteArray()
                )
                closeRemoteSocket()
            }
        }
    }

    fun forwardRequestToRemoteSocket(data: ByteArray) {
        runCatching {
            remoteSocket.getOutputStream().write(data)
        }.onFailure {
            LOGGER.error("[$clientId:$appId]-->[$remoteAddress:$remotePort]发生错误[${it.message}]")
            closeRemoteSocket()
        }
    }

    fun closeRemoteSocket() {
        proxyClientManager.unregisterAppRequest(this)
        runCatching {
            job.cancel()
        }
        runCatching {
            remoteSocket.close()
        }
    }

    companion object {
        val LOGGER = logger()
    }
}