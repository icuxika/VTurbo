package com.icuxika.vturbo.server

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
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

    /**
     * clientId:appId -> appRequest
     */
    private val manageableAppRequestMap = ConcurrentHashMap<String, ManageableAppRequest>()
    private val key: (x: Int, y: Int) -> String = { x, y -> "$x:$y" }

    private val mutex = Mutex()

    init {
        LOGGER.info("新客户端建立连接，id->$clientId")
    }

    /**
     * 开启请求代理
     */
    fun startRequestProxy() {
        scope.launch {
            while (true) {
                try {
                    client.getInputStream().readCompletePacket(LOGGER).let { packet ->
                        // 此时读取到了一个完整的Packet
                        val appId = packet.appId
                        val instructionId = packet.instructionId
                        val length = packet.length
                        val data = packet.data
                        when (instructionId) {
                            ProxyInstruction.CONNECT.instructionId -> {
                                // 读取目标服务器地址和端口，Socks 5协议传递的端口占用两个字节
                                val remoteAddressBytes = data.sliceArray(0 until length - 2)
                                val remotePortBytes = data.sliceArray(length - 2 until length)
                                val remoteAddress = InetAddress.getByName(String(remoteAddressBytes))
                                val remotePort = ByteBuffer.wrap(remotePortBytes).getShort()

                                ManageableAppRequest(
                                    scope,
                                    {
                                        sendRequestDataToProxyClient(it)
                                    },
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
                    LOGGER.error("代理客户端[$clientId]与代理服务端之间的连接出现了问题[${e.message}]")
                    // TODO 目标服务器断开的情况没处理
                    e.printStackTrace()
                    // 关闭客户端下的所有app与目标服务器的连接
                    manageableAppRequestMap.forEach { (k, v) ->
                        val (clientId0, _) = k.split(":")
                        if (clientId0.toInt() == clientId) {
                            v.closeRemoteSocket()
                        }
                    }
                    break
                }
            }
        }
    }

    private suspend fun sendRequestDataToProxyClient(data: ByteArray) {
        mutex.withLock {
            try {
                if (client.isConnected && !client.isClosed) {
                    client.getOutputStream().write(data)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                client.close()
                LOGGER.error("向代理客户端转发请求数据时遇到了错误[${e.message}]")
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
    private val sendRequestDataToProxyClient: suspend (data: ByteArray) -> Unit,
    private val clientId: Int,
    private val appId: Int,
    private val remoteAddress: InetAddress,
    private val remotePort: Short,
    private val onConnectionSuccess: (manageableAppRequest: ManageableAppRequest) -> Unit,
    private val onError: () -> Unit
) {
    private lateinit var remoteSocket: Socket

    init {
        LOGGER.info("客户端[$clientId]管理的App[$appId]尝试与目标服务器[$remoteAddress:$remotePort]建立连接")
        startRequestProxy()
    }

    private fun startRequestProxy() {
        scope.launch {
            remoteSocket = Socket()
            try {
                remoteSocket.connect(InetSocketAddress(remoteAddress, remotePort.toInt()))
                // 通知客户端连接连接成功开始转发请求数据
                sendRequestDataToProxyClient(
                    Packet(
                        appId,
                        ProxyInstruction.CONNECT.instructionId,
                        0,
                        byteArrayOf()
                    ).toByteArray()
                )
                onConnectionSuccess(this@ManageableAppRequest)
                LOGGER.info("客户端[$clientId]管理的App[$appId]与目标服务器[$remoteAddress:$remotePort]建立连接成功")

                val buffer = ByteArray(1024)
                var bytesRead: Int
                remoteSocket.use {
                    while (true) {
                        try {
                            bytesRead = it.getInputStream().read(buffer)
                            if (bytesRead > 0) {
                                sendRequestDataToProxyClient(
                                    Packet(
                                        appId,
                                        ProxyInstruction.SEND.instructionId,
                                        bytesRead,
                                        buffer.sliceArray(0 until bytesRead)
                                    ).toByteArray()
                                )
                            } else if (bytesRead == -1) {
                                break
                            }
                        } catch (e: IOException) {
                            // 此处异常处理针对 InputStream.read
                            // sendRequestDataToProxyClient 内部异常内部处理
                            LOGGER.warn("客户端[$clientId]管理的App[$appId]与目标服务器[$remoteAddress:$remotePort]的连接中断")
                            closeRemoteSocket()
                            // 通知客户端有个App与目标服务器之间的连接断开
                            sendRequestDataToProxyClient(
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
                    closeRemoteSocket()
                }
            } catch (e: Exception) {
                LOGGER.warn("客户端[$clientId]管理的App[$appId]与目标服务器[$remoteAddress:$remotePort]建立连接失败")
                closeRemoteSocket()
                onError()
            }
        }
    }

    /**
     * 转发请求数据
     */
    fun sendRequestData(data: ByteArray) {
        try {
            remoteSocket.getOutputStream().write(data)
        } catch (e: Exception) {
            e.printStackTrace()
            ClientContextHolder.LOGGER.warn("向目标服务器转发请求数据的时候遇到了错误[${e.message}]");
        }
    }

    /**
     * 关闭与目标服务器的连接
     */
    fun closeRemoteSocket() {
        try {
            remoteSocket.close()
        } catch (e: Exception) {
            LOGGER.warn("关闭app[$clientId:$appId]所访问的目标服务器Socket时发生错误[${e.message}]")
        }
    }

    companion object {
        val LOGGER = logger()
    }
}