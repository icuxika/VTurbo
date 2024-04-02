package com.icuxika.vturbo.client.protocol

import com.icuxika.vturbo.client.server.ProxyServer
import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

abstract class AbstractProtocolHandle(
    private val proxyServer: ProxyServer,
    open val scope: CoroutineScope
) : ProtocolHandle {

    /**
     * 用于转发请求数据到app
     */
    private val bytesToAppChannel = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * 预备任务
     */
    fun beforeHandshake() {
        scope.launch {
            runCatching {
                for (data in bytesToAppChannel) {
                    forwardRequestToApp(data)
                }
            }.onFailure {
                LOGGER.error("从Channel中读取请求数据发生错误[${it.message}]", it)
                shutdownAbnormally()
            }
        }
    }

    /**
     * 向ProxyServerManager注册
     */
    fun registerToProxyServerManager() {
        proxyServer.registerProtocolHandle(this)
    }

    /**
     * 从ProxyServerManager中取消注册
     */
    private fun unregisterFromProxyServerManager() {
        proxyServer.unregisterProtocolHandle(this)
    }

    /**
     * 转发请求到代理服务端
     */
    fun forwardRequestToServer(data: ByteArray) {
        proxyServer.forwardRequestToProxyServer(data)
    }

    /**
     * 实际转发请求到app的操作
     */
    abstract suspend fun forwardRequestToApp(data: ByteArray)

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun forwardRequestToChannelOfApp(data: ByteArray) {
        runCatching {
            if (!bytesToAppChannel.isClosedForSend) {
                bytesToAppChannel.send(data)
            }
        }.onFailure {
            LOGGER.error("向Channel发送请求数据发生错误[${it.message}]", it)
            shutdownAbnormally()
        }
    }

    override fun shutdownGracefully() {
        unregisterFromProxyServerManager()
        runCatching {
            bytesToAppChannel.close()
        }
    }

    override fun shutdownAbnormally() {
        unregisterFromProxyServerManager()
        runCatching {
            bytesToAppChannel.close()
        }
    }

    companion object {
        val LOGGER = logger()
    }
}