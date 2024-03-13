package com.icuxika.vturbo.client

import com.icuxika.vturbo.client.protocol.Socks5ProtocolHandle
import com.icuxika.vturbo.client.server.ProxyServerManager
import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.CoroutineScope
import java.net.Socket

class AppRequestContextHolder(
    proxyServerManager: ProxyServerManager,
    override val scope: CoroutineScope,
    override val client: Socket,
    override val appId: Int
) : Socks5ProtocolHandle(proxyServerManager, scope, client, appId) {

    fun startRequestProxy() {
        beforeHandshake()
        startHandshake()
    }

    companion object {
        val LOGGER = logger()
    }
}