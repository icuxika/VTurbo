package com.icuxika.vturbo.server

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ProxyServer {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    private lateinit var serverSocket: ServerSocket

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
        LOGGER.error("捕获到异常 $exception")
    }

    private val clientIdGenerator = AtomicInteger(0)

    /**
     * 客户端 -> app
     */
    private val clientAppMap = ConcurrentHashMap<Int, ArrayList<Int>>()

    /**
     * app -> socket
     */
    private val appSocketMap = ConcurrentHashMap<String, Socket>()

    fun launchServer(port: Int) {
        LOGGER.info("服务启动端口->$port")
        serverSocket = ServerSocket(port)

        while (true) {
            val client = serverSocket.accept()
            val clientId = clientIdGenerator.getAndIncrement()
            scope.launch(exceptionHandler) {
                LOGGER.info("当前客户端id->${clientId}")
                while (true) {
                    client.getInputStream().readCompletePacket(LOGGER)?.let { packet ->
                        val appId = packet.appId
                        val instructionId = packet.instructionId
                        val length = packet.length
                        val data = packet.data
                        when (instructionId) {
                            ProxyInstruction.CONNECT.instructionId -> {
                                // 建立连接
                                LOGGER.info("read length: $length")
                                val remoteAddressBytes = data.sliceArray(0 until length - 2)
                                val remoteAddress = InetAddress.getByAddress(remoteAddressBytes)
                                val remotePort: Short = 80
                                LOGGER.info("app[$clientId:$appId]要访问的目标服务器地址->$remoteAddress:$remotePort")
                                tryEstablishConnect(client, clientId, appId, remoteAddress, remotePort)
                            }

                            ProxyInstruction.SEND.instructionId -> {
                                LOGGER.info("转发数据长度->$length")
                                // 接收数据
                                appSocketMap["$clientId:$appId"]?.getOutputStream()
                                    ?.write(data)
                            }

                            ProxyInstruction.RESPONSE.instructionId -> {
                                // 回应数据
                            }

                            ProxyInstruction.DISCONNECT.instructionId -> {
                                // 关闭连接
                                appSocketMap["$clientId:$appId"]?.close()
                                appSocketMap.remove("$clientId:$appId")
                                clientAppMap[clientId]?.removeIf { it == appId }
                            }

                            else -> {
                                LOGGER.warn("异常指令")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun tryEstablishConnect(
        client: Socket,
        clientId: Int,
        appId: Int,
        remoteAddress: InetAddress,
        remotePort: Short
    ) {
        scope.launch(exceptionHandler) {
            LOGGER.info("app[$clientId:$appId]尝试建立连接")
            val remoteSocket = Socket()
            try {
                remoteSocket.connect(InetSocketAddress(InetAddress.getByName("www.baidu.com"), remotePort.toInt()))
                // 建立app与socket之间的关系
                appSocketMap["$clientId:$appId"] = remoteSocket
                val appIdList = clientAppMap.computeIfAbsent(clientId) { _ -> ArrayList() }
                appIdList.add(appId)
                // 通知连接成功
                client.getOutputStream()
                    .write(Packet(appId, ProxyInstruction.CONNECT.instructionId, 0, byteArrayOf()).toByteArray())
                LOGGER.info("app[$clientId:$appId]建立连接成功")

                val buffer = ByteArray(1024)
                var bytesRead: Int
                // 开启死循环转发目标服务器的请求数据到代理客户端
                remoteSocket.getInputStream().use { remoteInput ->
                    while (true) {
                        try {
                            bytesRead = remoteInput.read(buffer)
                            if (bytesRead != -1) {
                                client.getOutputStream().write(
                                    Packet(
                                        appId,
                                        ProxyInstruction.SEND.instructionId,
                                        bytesRead,
                                        buffer.sliceArray(0 until bytesRead)
                                    ).toByteArray()
                                )
                            }
                        } catch (e: IOException) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                LOGGER.warn("app[$appId]建立连接失败")
                // 通知连接失败
                client.getOutputStream()
                    .write(Packet(appId, ProxyInstruction.DISCONNECT.instructionId, 0, byteArrayOf()).toByteArray())
            }
        }
    }

    companion object {
        val LOGGER = logger()
    }
}