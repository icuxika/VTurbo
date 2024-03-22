package com.icuxika.vturbo.client

import com.icuxika.vturbo.client.protocol.Socks5HandshakeStatus
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
    private val scope = CoroutineScope(Dispatchers.IO + supervisor + CoroutineName("NProxyClient"))

    private val readBuffer = ByteBuffer.allocate(1024)
    private val clientMap = mutableMapOf<SocketChannel, NAppRequestContextHolder>()
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
                        clientChannel
                            .configureBlocking(false)
                            .register(
                                selector,
                                SelectionKey.OP_READ,
                                Socks5HandshakeStatus.ASK_ABOUT_AUTHENTICATION_METHOD
                            )

                        clientMap[clientChannel] =
                            NAppRequestContextHolder(
                                clientChannel,
                                proxyServerManager,
                                scope,
                                appIdGenerator.getAndIncrement()
                            )
                    }

                    key.isReadable -> {
                        val clientChannel = key.channel() as SocketChannel
                        val attachment = key.attachment() as Socks5HandshakeStatus
                        val nAppRequestContextHolder = clientMap[clientChannel] ?: throw RuntimeException("数据异常")
                        readBuffer.clear()
                        runCatching {
                            val bytesRead = clientChannel.read(readBuffer)
                            if (bytesRead == -1) {
                                if (attachment == Socks5HandshakeStatus.START_FORWARDING_REQUEST_DATA) {
                                    LOGGER.info("[${nAppRequestContextHolder.appId}]请求正常结束")
                                    nAppRequestContextHolder.notifyProxyServerRequestHasEnded()
                                }
                                nAppRequestContextHolder.shutdownGracefully()
                                key.cancel()
                                clientChannel.close()
                            }
                            if (bytesRead > 0) {
                                readBuffer.flip()

                                when (attachment) {
                                    Socks5HandshakeStatus.ASK_ABOUT_AUTHENTICATION_METHOD -> {
                                        nAppRequestContextHolder.handshake1(readBuffer)
                                        key.attach(Socks5HandshakeStatus.TRANSFER_TARGET_SERVER_INFORMATION)
                                    }

                                    Socks5HandshakeStatus.TRANSFER_TARGET_SERVER_INFORMATION -> {
                                        nAppRequestContextHolder.handshake2(readBuffer)
                                        key.attach(Socks5HandshakeStatus.START_FORWARDING_REQUEST_DATA)
                                    }

                                    Socks5HandshakeStatus.START_FORWARDING_REQUEST_DATA -> {
                                        val byteArray = ByteArray(bytesRead)
                                        readBuffer.get(byteArray)
                                        nAppRequestContextHolder.forwardRequestToServer(bytesRead, byteArray)
                                    }
                                }
                            }
                        }.onFailure {
                            LOGGER.info("[${nAppRequestContextHolder.appId}][${attachment.status}]遇到错误[${it.message}]")
                            nAppRequestContextHolder.notifyProxyServerRequestHasEnded(false)
                            nAppRequestContextHolder.shutdownGracefully()
                            clientChannel.close()
                        }
                    }
                }
            }
            selector.selectedKeys().clear()
        }
    }

    companion object {
        val LOGGER = logger()
    }
}