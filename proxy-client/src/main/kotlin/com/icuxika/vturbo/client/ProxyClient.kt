package com.icuxika.vturbo.client

import com.icuxika.vturbo.client.protocol.bio.AppRequestContextHolder
import com.icuxika.vturbo.client.server.ProxyServerManager
import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class ProxyClient {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("ProxyServerManager"))

    private lateinit var serverSocket: ServerSocket
    private val appIdGenerator = AtomicInteger(0)

    fun launchServer(port: Int, proxyServerAddress: String) {
        LOGGER.info("服务监听端口->$port")
        val proxyServerManager = ProxyServerManager(proxyServerAddress)
        serverSocket = ServerSocket(port)
        while (true) {
            val client = serverSocket.accept()
            val appId = appIdGenerator.getAndIncrement()
            AppRequestContextHolder(client, proxyServerManager, scope, appId)
        }
    }

    companion object {
        val LOGGER = logger()
    }
}