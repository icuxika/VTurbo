package com.icuxika.vturbo.server

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.server.client.ProxyClientManager
import com.icuxika.vturbo.server.server.TargetServerManager
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class ProxyServer {

    private lateinit var serverSocket: ServerSocket

    private val clientIdGenerator = AtomicInteger(0)

    fun launchServer(port: Int) {
        LOGGER.info("服务监听端口->$port")

        // 负责连接目标服务器，转发请求数据
        val targetServerManager = TargetServerManager()
        targetServerManager.mainLoop()

        serverSocket = ServerSocket(port)
        while (true) {
            val client = serverSocket.accept()
            val clientId = clientIdGenerator.getAndIncrement()

            ProxyClientManager(targetServerManager, client, clientId).startRequestProxy()
        }
    }

    companion object {
        val LOGGER = logger()
    }
}