package com.icuxika.vturbo.server

import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer

class ProxyServer {

    private lateinit var serverSocket: ServerSocket

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        println("CoroutineExceptionHandler got $exception")
    }

    fun launchServer(port: Int) {
        LOGGER.info("服务启动端口：$port")
        serverSocket = ServerSocket(port)

        runBlocking {
            while (true) {
                val client = serverSocket.accept()

                val supervisor = SupervisorJob()
                with(CoroutineScope(Dispatchers.IO + supervisor)) {
                    launch(exceptionHandler) {
                        val buffer = ByteBuffer.allocate(6)
                        val bytesRead = client.getInputStream().read(buffer.array())
                        LOGGER.info("读取字节数：$bytesRead")

                        val remoteIpBytes = ByteArray(4)
                        buffer.get(remoteIpBytes)
                        val remoteInetAddress = InetAddress.getByAddress(remoteIpBytes)
                        LOGGER.info("远程ip：$remoteInetAddress")

                        val remotePort = buffer.getShort()
                        LOGGER.info("远程port: $remotePort")
                    }
                }
            }
        }
    }

    companion object {
        val LOGGER = logger()
    }
}