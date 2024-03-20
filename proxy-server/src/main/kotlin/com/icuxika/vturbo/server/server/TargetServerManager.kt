package com.icuxika.vturbo.server.server

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.toByteArray
import com.icuxika.vturbo.server.client.ProxyClientManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class TargetServerManager {

    private lateinit var selector: Selector

    /**
     * [代理客户端Id:appId] -> 目标服务器
     */
    private val serverChannelMap = ConcurrentHashMap<String, SocketChannel>()
    private val generateAppKey: (x: Int, y: Int) -> String = { x, y -> "$x:$y" }

    /**
     * 代理客户端Id -> 代理客户端
     */
    private val clientManagerMap = ConcurrentHashMap<Int, ProxyClientManager>()

    private val readBuffer = ByteBuffer.allocate(1024)

    private val forwardDataToTargetServerQueue = ArrayBlockingQueue<ClientPacket>(100)

    fun mainLoop() {
        // 不断转发代理客户端的请求数据到目标服务端
        thread {
            while (true) {
                forwardDataToTargetServerQueue.take().let { clientPacket ->
                    val (clientId, appId, data) = clientPacket
                    forwardRequestToServer(clientId, appId, data)
                }
            }
        }

        // 主线，不断处理目标服务器的相关就绪事件
        thread {
            selector = Selector.open()
            while (true) {
                selector.select()
                val iterator = selector.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()

                    when {
                        key.isConnectable -> {
                            handleConnectable(key)
                        }

                        key.isReadable -> {
                            handleReadable(key)
                        }
                    }
                }
                selector.selectedKeys().clear()
            }
        }
    }

    /**
     * 处理新连接
     */
    private fun handleConnectable(selectionKey: SelectionKey) {
        val socketChannel = selectionKey.channel() as SocketChannel
        val appKey = selectionKey.attachment() as String
        serverChannelMap[appKey] = socketChannel
        val (clientId, appId) = appKey.split(":")

        runCatching {
            if (socketChannel.isConnectionPending) {
                socketChannel.finishConnect()
            }
            socketChannel.register(selector, SelectionKey.OP_READ, appKey)
            forwardRequestToProxyClient(
                clientId.toInt(),
                Packet(
                    appId.toInt(),
                    ProxyInstruction.CONNECT.instructionId,
                    0,
                    byteArrayOf()
                ).toByteArray()
            )
        }.onFailure {
            LOGGER.warn("[$clientId:$appId]无法连接目标服务器[${it.message}]")
            forwardRequestToProxyClient(
                clientId.toInt(),
                Packet(
                    appId.toInt(),
                    ProxyInstruction.EXCEPTION_DISCONNECT.instructionId,
                    0,
                    byteArrayOf()
                ).toByteArray()
            )
            serverChannelMap.remove(appKey)
            selectionKey.cancel()
            socketChannel.close()
        }
    }

    /**
     * 处理可读事件
     */
    private fun handleReadable(selectionKey: SelectionKey) {
        val socketChannel = selectionKey.channel() as SocketChannel
        val appKey = selectionKey.attachment() as String
        val (clientId, appId) = appKey.split(":")
        readBuffer.clear()
        runCatching {
            val bytesRead = socketChannel.read(readBuffer)
            if (bytesRead == -1) {
                LOGGER.info("[$clientId:$appId]对目标服务器的请求已经结束")
                forwardRequestToProxyClient(
                    clientId.toInt(),
                    Packet(
                        appId.toInt(),
                        ProxyInstruction.DISCONNECT.instructionId,
                        0,
                        byteArrayOf()
                    ).toByteArray()
                )
                serverChannelMap.remove(appKey)
                selectionKey.cancel()
                socketChannel.close()
            }
            if (bytesRead > 0) {
                readBuffer.flip()
                val byteArray = ByteArray(bytesRead)
                readBuffer.get(byteArray)
                // 写入数据到app
                forwardRequestToProxyClient(
                    clientId.toInt(),
                    Packet(
                        appId.toInt(),
                        ProxyInstruction.SEND.instructionId,
                        bytesRead,
                        byteArray
                    ).toByteArray()
                )
            }
        }.onFailure {
            LOGGER.error("[$clientId:$appId]读取目标服务器数据出现错误[${it.message}]")
            forwardRequestToProxyClient(
                clientId.toInt(),
                Packet(
                    appId.toInt(),
                    ProxyInstruction.EXCEPTION_DISCONNECT.instructionId,
                    0,
                    byteArrayOf()
                ).toByteArray()
            )
            serverChannelMap.remove(appKey)
            selectionKey.cancel()
            socketChannel.close()
        }
    }

    /**
     * 连接到目标服务器
     */
    fun connectToServer(clientId: Int, appId: Int, remoteAddress: InetAddress, remotePort: Short) {
        LOGGER.info("新连接[$clientId:$appId]---->[$remoteAddress:$remotePort]")
        runCatching {
            SocketChannel.open().apply {
                configureBlocking(false)
                connect(InetSocketAddress(remoteAddress, remotePort.toInt()))
                register(selector, SelectionKey.OP_CONNECT, generateAppKey(clientId, appId))
            }
            selector.wakeup()
        }.onFailure {
            LOGGER.error("新连接[$clientId:$appId]---->[$remoteAddress:$remotePort]注册失败[${it.message}]")
        }
    }

    /**
     * 转发请求数据到目标服务器
     */
    private fun forwardRequestToServer(clientId: Int, appId: Int, data: ByteArray) {
        serverChannelMap[generateAppKey(clientId, appId)]?.let { socketChannel ->
            val buffer = ByteBuffer.wrap(data)
            var totalBytesWritten = 0
            runCatching {
                while (buffer.hasRemaining()) {
                    val bytesWritten = socketChannel.write(buffer)
                    totalBytesWritten += bytesWritten
                    if (bytesWritten == 0) {
                        // 如果写入的字节数为0，则可能是底层网络缓冲区已满，暂停一段时间再试
                        LOGGER.warn("[$clientId:$appId]-----系统底层网路缓冲区可能满了")
                        Thread.sleep(10)
                    }
                }
            }.onFailure {
                LOGGER.error("向[$clientId:$appId]的目标服务器写入数据时遇到错误[${it.message}]")
            }

            if (totalBytesWritten < data.size) {
                LOGGER.warn("向[$clientId:$appId]的目标服务器写入数据时未能完整写入数据，共有[${data.size}] Bytes，实际写入[$totalBytesWritten] Bytes")
            }
        }
    }

    @Synchronized
    fun forwardRequestToTargetServer(clientPacket: ClientPacket) {
        forwardDataToTargetServerQueue.put(clientPacket)
    }

    /**
     * 转发请求数据到代理客户端
     */
    private fun forwardRequestToProxyClient(clientId: Int, data: ByteArray) {
        clientManagerMap[clientId]?.forwardRequestToProxyClient(data)
    }

    /**
     * 主动关闭目标服务器
     */
    fun closeSocketChannel(clientId: Int, appId: Int) {
        val appKey = generateAppKey(clientId, appId)
        runCatching {
            serverChannelMap[appKey]?.close()
        }
        serverChannelMap.remove(appKey)
    }

    /**
     * 关闭由与代理客户端关联的所有目标服务器
     */
    fun closeAllChannelByClientId(clientIdToClose: Int) {
        serverChannelMap.forEach { (k, v) ->
            val (clientId, appId) = k.split(":")
            if (clientId.toInt() == clientIdToClose) {
                runCatching {
                    v.close()
                }
            }
        }
    }

    /**
     * 注册ProxyClientManager
     */
    fun registerClientManager(proxyClientManager: ProxyClientManager) {
        clientManagerMap[proxyClientManager.clientId] = proxyClientManager
    }

    /**
     * 取消注册ProxyClientManager
     */
    fun unregisterClientManager(proxyClientManager: ProxyClientManager) {
        clientManagerMap.remove(proxyClientManager.clientId)
    }

    companion object {
        val LOGGER = logger()
    }
}