package com.icuxika.vturbo.client.protocol

import com.icuxika.vturbo.client.server.ProxyServerManager
import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

abstract class AbstractProtocolHandle(
    private val proxyServerManager: ProxyServerManager,
    open val scope: CoroutineScope
) : ProtocolHandle {

    /**
     * 用于转发请求数据到app
     */
    private val bytesToAppChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override fun beforeHandshake() {
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

    override fun startHandshake() {

    }

    override fun afterHandshake() {

    }

    override fun registerToProxyServerManager() {
        proxyServerManager.registerProtocolHandle(this)
    }

    override fun unregisterFromProxyServerManager() {
        proxyServerManager.unregisterProtocolHandle(this)
    }

    override fun forwardRequestToServer(data: ByteArray) {
        proxyServerManager.forwardRequestToProxyServer(data)
    }

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