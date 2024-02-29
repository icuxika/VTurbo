package com.icuxika.vturbo.server

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 每一个[ClientContextHolder]对应一个客户端
 */
class ClientContextHolder(private val client: Socket, private val clientId: Int) {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("client:$clientId"))
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
        ProxyServer.LOGGER.error("协程运行时捕获到异常 $exception")
    }

    private val clientInput = client.getInputStream()
    private val clientOutput = client.getOutputStream()

    /**
     * clientId:appId -> appRequest
     */
    private val manageableAppRequestMap = ConcurrentHashMap<String, ManageableAppRequest>()
    private val key: (x: Int, y: Int) -> String = { x, y -> "$x:$y" }

    init {
        LOGGER.info("新客户端建立连接，id->$clientId")
    }

    /**
     * 开启请求代理
     */
    fun startRequestProxy() {
        scope.launch(exceptionHandler) {
            while (true) {
                try {
                    clientInput.readCompletePacket(LOGGER)?.let { packet ->
                        // 此时读取到了一个完整的Packet
                        val appId = packet.appId
                        val instructionId = packet.instructionId
                        val length = packet.length
                        val data = packet.data
                        when (instructionId) {
                            ProxyInstruction.CONNECT.instructionId -> {
                                // 读取目标服务器地址和端口，Socks 5协议传递的端口占用两个字节
                                // TODO IPv4、IPv6、域名不同格式的处理
                                val remoteAddressBytes = data.sliceArray(0 until length - 2)
                                val remotePortBytes = data.sliceArray(length - 2 until length)
                                val remoteAddress = InetAddress.getByName(String(remoteAddressBytes))
                                val remotePort = ByteBuffer.wrap(remotePortBytes).getShort()

                                ManageableAppRequest(
                                    scope,
                                    clientOutput,
                                    clientId,
                                    appId,
                                    remoteAddress,
                                    remotePort,
                                    {
                                        manageableAppRequestMap[key(clientId, appId)] = it
                                    },
                                    {
                                        manageableAppRequestMap.remove(key(clientId, appId))
                                    }
                                )
                            }

                            ProxyInstruction.SEND.instructionId -> {
                                LOGGER.info("[$clientId][$appId]转发数据长度->$length")
                                manageableAppRequestMap[key(clientId, appId)]?.sendRequestData(data)
                            }

                            ProxyInstruction.RESPONSE.instructionId -> {}
                            ProxyInstruction.DISCONNECT.instructionId -> {
                                manageableAppRequestMap[key(clientId, appId)]?.closeRemoteSocket()
                                manageableAppRequestMap.remove(key(clientId, appId))
                            }

                            else -> {
                                LOGGER.warn("异常指令")
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOGGER.warn("代理客户端与代理服务端之间的连接出现了问题")
                    e.printStackTrace()
                    client.close()
                    break
                }
            }
        }
    }

    companion object {
        val LOGGER = logger()
    }
}

/**
 * 每一个[ManageableAppRequest]对象对应一个从不同客户端转发过来的一个应用
 */
class ManageableAppRequest(
    private val scope: CoroutineScope,
    private val clientOutput: OutputStream,
    private val clientId: Int,
    private val appId: Int,
    private val remoteAddress: InetAddress,
    private val remotePort: Short,
    private val onConnectionSuccess: (manageableAppRequest: ManageableAppRequest) -> Unit,
    private val onError: () -> Unit
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
        ProxyServer.LOGGER.error("协程运行时捕获到异常 $exception")
    }

    private lateinit var remoteSocket: Socket

    init {
        LOGGER.info("客户端[$clientId]管理的App[$appId]尝试与目标服务器[$remoteAddress:$remotePort]建立连接")
        startRequestProxy()
    }

    private fun startRequestProxy() {
        scope.launch(exceptionHandler) {
            remoteSocket = Socket()
            try {
                remoteSocket.connect(InetSocketAddress(remoteAddress, remotePort.toInt()))
                // 通知客户端连接连接成功开始转发请求数据
                clientOutput.write(
                    Packet(
                        appId,
                        ProxyInstruction.CONNECT.instructionId,
                        0,
                        byteArrayOf()
                    ).toByteArray()
                )
                onConnectionSuccess(this@ManageableAppRequest)
                LOGGER.info("客户端[$clientId]管理的App[$appId]与目标服务器[$remoteAddress:$remotePort]建立连接成功")

                val buffer = ByteArray(128)
                var bytesRead: Int
                remoteSocket.getInputStream().use { remoteInput ->
                    while (true) {
                        try {
                            bytesRead = remoteInput.read(buffer)
                            if (bytesRead != -1) {
                                LOGGER.info("发送到代理客户端的数据长度->$bytesRead")
                                clientOutput.write(
                                    Packet(
                                        appId,
                                        ProxyInstruction.SEND.instructionId,
                                        bytesRead,
                                        buffer.sliceArray(0 until bytesRead)
                                    ).toByteArray()
                                )
                            }
                        } catch (e: IOException) {
                            LOGGER.warn("客户端[$clientId]管理的App[$appId]与目标服务器[$remoteAddress:$remotePort]的连接中断")
                            remoteSocket.close()
                            // 通知客户端有个App与目标服务器之间的连接断开
                            clientOutput.write(
                                Packet(
                                    appId,
                                    ProxyInstruction.DISCONNECT.instructionId,
                                    0,
                                    byteArrayOf()
                                ).toByteArray()
                            )
                            onError()
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                LOGGER.warn("客户端[$clientId]管理的App[$appId]与目标服务器[$remoteAddress:$remotePort]建立连接失败")
                onError()
            }
        }
    }

    /**
     * 转发请求数据
     */
    fun sendRequestData(data: ByteArray) {
        remoteSocket.getOutputStream().write(data)
    }

    /**
     * 关闭与目标服务器的连接
     */
    fun closeRemoteSocket() {
        remoteSocket.close()
    }

    companion object {
        val LOGGER = logger()
    }
}