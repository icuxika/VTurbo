package com.icuxika.vturbo.client.protocol

import com.icuxika.vturbo.client.AppSocketStatus
import com.icuxika.vturbo.client.server.ProxyServerManager
import com.icuxika.vturbo.commons.extensions.isConnecting
import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.Socket

/**
 * 面向ProxyServerManager，负责在app和代理服务器之间转发请求数据
 */
abstract class AbstractProtocolHandle(
    private val proxyServerManager: ProxyServerManager,
    open val scope: CoroutineScope,
    open val client: Socket,
    open val appId: Int
) : ProtocolHandle {

    /**
     * 用于接收要发送到app的数据
     */
    private val bytesToAppChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override fun beforeHandshake() {
        scope.launch {
            runCatching {
                for (data in bytesToAppChannel) {
                    if (client.isConnecting()) {
                        client.getOutputStream().write(data)
                    }
                }
            }.onFailure {
                clean()
            }
        }
    }

    override suspend fun forwardRequestToServer(data: ByteArray): Boolean {
        return runCatching {
            proxyServerManager.sendRequestDataToProxyServer(appId, data)
        }.fold(
            onSuccess = { true },
            onFailure = { e ->
                LOGGER.error("发送数据到代理服务端的时候出错[${e.message}]")
                clean()
                false
            }
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun forwardRequestToApp(data: ByteArray, appSocketStatus: AppSocketStatus) {
        runCatching {
            if (!bytesToAppChannel.isClosedForSend) {
                bytesToAppChannel.send(data)
            }
        }.onFailure {
            LOGGER.warn("发送数据到app channel时出现了错误[${it.message}]")
            unregisterFromProxyServerManager()
            clean()
        }
    }

    override fun registerToProxyServerManager() {
        proxyServerManager.registerProtocolHandle(this)
    }

    override fun unregisterFromProxyServerManager() {
        proxyServerManager.unregisterProtocolHandle(this)
    }

    override fun clean() {
        runCatching {
            client.close()
        }.onFailure {
            Socks5ProtocolHandle.LOGGER.warn("关闭app socket时发生错误[${it.message}]")
        }

        runCatching {
            bytesToAppChannel.close()
        }.onFailure {
            Socks5ProtocolHandle.LOGGER.warn("关闭channel时发生错误[${it.message}]")
        }
    }

    companion object {
        val LOGGER = logger()
    }
}