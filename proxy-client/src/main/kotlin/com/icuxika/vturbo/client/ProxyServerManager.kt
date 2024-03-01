package com.icuxika.vturbo.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.readCompletePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理与代理服务器之间的Socket链接，为app与目标服务器之间的请求数据进行转发
 */
class ProxyServerManager(proxyServerAddress: String) {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("ProxyServerManager"))
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        proxyServerSocket.close()
        LOGGER.error("[协程]等待读取代理服务端的数据时捕获到异常->[${exception.message}]，请检查代理服务器是否还在正常运行")
        // 已注册的app连接全部关闭
        appRequestMap.values.forEach { appRequestContextHolder ->
            appRequestContextHolder.closeAppSocket()
        }
    }

    private var proxyServerSocket: Socket = Socket()

    /**
     * appid <-> app socket
     */
    private val appRequestMap = ConcurrentHashMap<Int, AppRequestContextHolder>()

    private val mutex = Mutex()

    init {
        try {
            val (proxyServerHostname, proxyServerPort) = proxyServerAddress.split(":")
            LOGGER.info("代理服务器地址->$proxyServerHostname:$proxyServerPort")
            proxyServerSocket.connect(InetSocketAddress(proxyServerHostname, proxyServerPort.toInt()))
            LOGGER.info("与代理服务器建立连接成功")

            scope.launch(exceptionHandler) {
                proxyServerSocket.use {
                    while (true) {
                        it.getInputStream().readCompletePacket(LOGGER)?.let { packet ->
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
                                ProxyInstruction.DISCONNECT.instructionId -> {
                                    appRequestMap[appId]?.closeAppSocket()
                                }

                                else -> {}
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            LOGGER.error("无法与代理服务器建立连接")
            proxyServerSocket.close()
        }
    }

    /**
     * 发送请求数据到代理服务器
     */
    suspend fun sendRequestDataToProxyServer(appId: Int, data: ByteArray): Boolean {
        mutex.withLock {
            if (!proxyServerSocket.isClosed) {
                proxyServerSocket.getOutputStream().write(data)
                return true
            }
            LOGGER.warn("收到app[$appId]发送的请求数据，但是与代理服务器之间的连接已经关闭")
            return false
        }
    }

    /**
     * 确认app与要访问的目标服务器建立起链接后，注册到[ProxyServerManager.appRequestMap]中
     */
    fun registerAppRequest(appRequestContextHolder: AppRequestContextHolder) {
        appRequestMap[appRequestContextHolder.appId] = appRequestContextHolder
    }

    fun unregisterAppRequest(appRequestContextHolder: AppRequestContextHolder) {
        appRequestMap.remove(appRequestContextHolder.appId)
    }

    companion object {
        val LOGGER = logger()
    }
}