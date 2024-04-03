package com.icuxika.vturbo.client

import com.icuxika.vturbo.client.protocol.nio.NAppRequestContextHolder
import com.icuxika.vturbo.client.server.ProxyServerManager
import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger

class NProxyClient {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("NProxyClient"))

    private val readBuffer = ByteBuffer.allocate(1024)
    private val appIdGenerator = AtomicInteger(0)

    fun launchServer(port: Int, proxyServerAddress: String) {
        LOGGER.info("服务监听端口->$port")
        val proxyServerManager = ProxyServerManager(proxyServerAddress)

        val serverSocketChannel = ServerSocketChannel.open()
        serverSocketChannel.socket().bind(InetSocketAddress(port))
        serverSocketChannel.configureBlocking(false)

        val selector = Selector.open()
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT)

        while (true) {
            selector.select()
            val iterator = selector.selectedKeys().iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                iterator.remove()

                when {
                    key.isAcceptable -> {
                        val serverChannel = key.channel() as ServerSocketChannel
                        val clientChannel = serverChannel.accept()

                        val nAppRequestContextHolder = NAppRequestContextHolder(
                            clientChannel,
                            proxyServerManager,
                            scope,
                            appIdGenerator.getAndIncrement()
                        )

                        clientChannel
                            .configureBlocking(false)
                            .register(
                                selector,
                                SelectionKey.OP_READ,
                                nAppRequestContextHolder
                            )
                    }

                    key.isReadable -> {
                        val clientChannel = key.channel() as SocketChannel
                        val nAppRequestContextHolder = key.attachment() as NAppRequestContextHolder
                        readBuffer.clear()
                        runCatching {
                            val bytesRead = clientChannel.read(readBuffer)
                            if (bytesRead == -1) {
                                nAppRequestContextHolder.notifyProxyServerRequestHasEnded()
                                nAppRequestContextHolder.shutdownGracefully()
                                closeKeyAndChannel(key, clientChannel)
                            }
                            if (bytesRead > 0) {
                                readBuffer.flip()
                                nAppRequestContextHolder.handshakeOrForwardRequest(readBuffer, bytesRead)
                            }
                        }.onFailure {
                            LOGGER.error("[${nAppRequestContextHolder.getId()}][${nAppRequestContextHolder.socks5HandshakeStatus}]遇到错误[${it.message}]")
                            nAppRequestContextHolder.notifyProxyServerRequestHasEnded(false)
                            nAppRequestContextHolder.shutdownAbnormally()
                            closeKeyAndChannel(key, clientChannel)
                        }
                    }
                }
            }
            selector.selectedKeys().clear()
        }
    }

    /**
     * 关闭key和channel
     */
    private fun closeKeyAndChannel(key: SelectionKey, socketChannel: SocketChannel) {
        runCatching {
            key.cancel()
            socketChannel.close()
        }
    }

    companion object {
        val LOGGER = logger()
    }
}