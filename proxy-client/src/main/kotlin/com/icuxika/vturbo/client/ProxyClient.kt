package com.icuxika.vturbo.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class ProxyClient {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("ProxyServerManager"))
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
        ProxyServerManager.LOGGER.error("协程运行时捕获到异常 $exception")
    }

    private lateinit var serverSocket: ServerSocket
    private val appIdGenerator = AtomicInteger(0)

    fun launchServer(port: Int) {
        LOGGER.info("服务监听端口->$port")
        val proxyServerManager = ProxyServerManager()
        serverSocket = ServerSocket(port)
        while (true) {
            val client = serverSocket.accept()
            val appId = appIdGenerator.getAndIncrement()
            val appRequestContextHolder = AppRequestContextHolder(scope, proxyServerManager, client, appId)
            appRequestContextHolder.startRequestProxy()
        }
    }


    fun test() {
        val remoteSocket = Socket("127.0.0.1", 8882)
        val remoteInput = remoteSocket.getInputStream()
        val remoteOutput = remoteSocket.getOutputStream()

        runBlocking {
            val hostBytes = "180.101.50.188".split(".").map { it.toInt().toByte() }.toByteArray()
            val addressBytes = "www.baidu.com".toByteArray()

            val portBuffer = ByteBuffer.allocate(2)
            portBuffer.putShort(80)
            val portBytes = portBuffer.array()

            remoteOutput.write(
                Packet(
                    1,
                    ProxyInstruction.CONNECT.instructionId,
                    addressBytes.size + portBytes.size,
                    addressBytes + portBytes
                ).toByteArray()
            )
            remoteOutput.flush()

            val responsePacket = remoteInput.readCompletePacket(LOGGER)
            responsePacket?.let { packet ->
                if (packet.instructionId != ProxyInstruction.CONNECT.instructionId) {
                    LOGGER.error("与目标服务器的连接建立失败")
                    return@runBlocking
                }

                // 对于socks部分，此处应响应连接成功

                val text = "GET /baidu.html HTTP/1.1\r\nHost: www.baidu.com\r\nConnection: Close\r\n\r\n"
                val textBytes = text.toByteArray()
                LOGGER.info("size->${textBytes.size}")

                remoteOutput.write(
                    Packet(
                        1,
                        ProxyInstruction.SEND.instructionId,
                        textBytes.size,
                        textBytes
                    ).toByteArray()
                )
                remoteOutput.flush()

                var isFirst = true
                while (true) {
                    remoteInput.readCompletePacket(LOGGER)?.let { packet ->
                        LOGGER.info((if (isFirst) "\n" else "") + String(packet.data))
                        if (isFirst) isFirst = false
                    }
                }
            }
        }
    }

    companion object {
        val LOGGER = logger()
    }
}