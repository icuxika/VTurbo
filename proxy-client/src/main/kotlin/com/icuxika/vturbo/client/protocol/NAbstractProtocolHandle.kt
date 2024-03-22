package com.icuxika.vturbo.client.protocol

import com.icuxika.vturbo.client.server.ProxyServerManager
import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

abstract class NAbstractProtocolHandle(
    private val proxyServerManager: ProxyServerManager,
    open val scope: CoroutineScope,
    open val appId: Int
) : NProtocolHandle {

    private val bytesToAppChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var bytesToAppJob: Job? = null

    override fun beforeHandshake() {
        bytesToAppJob = scope.launch {
            runCatching {
                for (data in bytesToAppChannel) {
                    forwardRequestToApp(data)
                }
            }.onFailure {
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

    override suspend fun forwardRequestToChannelOfApp(data: ByteArray) {
        runCatching {
            bytesToAppChannel.send(data)
        }.onFailure {
            shutdownAbnormally()
        }
    }

    override fun shutdownGracefully() {
        unregisterFromProxyServerManager()
        runCatching {
            bytesToAppJob?.cancel()
            bytesToAppChannel.close()
        }
    }

    override fun shutdownAbnormally() {
        unregisterFromProxyServerManager()
        runCatching {
            bytesToAppJob?.cancel()
            bytesToAppChannel.close()
        }
    }

    companion object {
        val LOGGER = logger()
    }
}