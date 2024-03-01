package com.icuxika.vturbo.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class ProxyServerManager(private val proxyServerAddress: String) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("ProxyServerManager"))
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
        LOGGER.error("协程运行时捕获到异常 $exception")
    }

    private lateinit var proxyServerSocket: Socket

    private val appRequestMap = ConcurrentHashMap<Int, AppRequestContextHolder>()

    init {
        proxyServerSocket = Socket()
        try {
            val (proxyServerHostname, proxyServerPort) = proxyServerAddress.split(":")
            LOGGER.info("代理服务器地址->$proxyServerHostname:$proxyServerPort")
            proxyServerSocket.connect(InetSocketAddress(proxyServerHostname, proxyServerPort.toInt()))
            LOGGER.info("与代理服务器建立连接成功")

            scope.launch(exceptionHandler) {
                proxyServerSocket.getInputStream().use { proxyServerInput ->
                    while (true) {
                        proxyServerInput.readCompletePacket(LOGGER)?.let { packet ->
                            val appId = packet.appId
                            val instructionId = packet.instructionId
                            val length = packet.length
                            val data = packet.data
                            when (instructionId) {
                                ProxyInstruction.CONNECT.instructionId -> {
                                    appRequestMap[appId]?.afterHandshake()
                                }

                                ProxyInstruction.SEND.instructionId -> {
                                    appRequestMap[appId]?.sendRequestDataToApp(data)
                                }

                                ProxyInstruction.RESPONSE.instructionId -> {}
                                ProxyInstruction.DISCONNECT.instructionId -> {}
                                else -> {}
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendRequestDatToProxyServer(data: ByteArray) {
        proxyServerSocket.getOutputStream().write(data)
    }

    /**
     * 确认app与要访问的目标服务器建立起链接后，注册到[ProxyServerManager.appRequestMap]中
     */
    fun registerAppRequest(appRequestContextHolder: AppRequestContextHolder) {
        appRequestMap[appRequestContextHolder.appId] = appRequestContextHolder
    }

    companion object {
        val LOGGER = logger()
    }
}