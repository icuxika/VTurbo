package com.icuxika.vturbo.client.protocol.nio

import com.icuxika.vturbo.client.protocol.AbstractProtocolHandle
import com.icuxika.vturbo.client.protocol.Socks5HandshakeStatus
import com.icuxika.vturbo.client.protocol.Socks5HandshakeStatus.*
import com.icuxika.vturbo.client.server.ProxyServer
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * app请求上下文
 */
class NAppRequestContextHolder(
    private val clientChannel: SocketChannel,
    proxyServer: ProxyServer,
    override val scope: CoroutineScope,
    private val appId: Int
) : AbstractProtocolHandle(proxyServer, scope) {

    /**
     * SOCKS5握手状态
     */
    var socks5HandshakeStatus: Socks5HandshakeStatus = ASK_ABOUT_AUTHENTICATION_METHOD

    /**
     * app要访问的目标服务器和端口
     */
    private lateinit var remoteAddress: String
    private var remotePort: Short = 0

    /**
     * app告诉代理客户端要访问的目标服务器，需要在握手成功时返回给app
     */
    private var remoteAddressTypeByte: Byte = 0
    private lateinit var remoteAddressBytes: ByteArray
    private lateinit var remotePortBytes: ByteArray

    init {
        beforeHandshake()
    }

    override fun getId(): Int {
        return appId
    }

    override suspend fun forwardRequestToApp(data: ByteArray) {
        if (!clientChannel.isOpen) {
            return
        }
        val buffer = ByteBuffer.wrap(data)
        var totalBytesWritten = 0
        runCatching {
            while (buffer.hasRemaining()) {
                val bytesWritten = clientChannel.write(buffer)
                totalBytesWritten += bytesWritten
                if (bytesWritten == 0) {
                    // 如果写入的字节数为0，则可能是底层网络缓冲区已满，暂停一段时间再试
                    LOGGER.warn("[$appId]-----系统底层网路缓冲区可能满了")
                    delay(10)
                }
            }
        }.onFailure {
            LOGGER.error("向[$appId]写入数据时遇到错误[${it.message}]", it)
        }

        if (totalBytesWritten < data.size) {
            LOGGER.warn("向[$appId]写入数据时未能完整写入数据，共有[${data.size}] Bytes，实际写入[$totalBytesWritten] Bytes")
        }
    }

    /**
     * 处理SOCKS5握手过程及握手成功后的请求转发
     * 目前收到SOCKS5握手请求后，会根据握手状态进行不同的处理，
     * 对于[ASK_ABOUT_AUTHENTICATION_METHOD]和[TRANSFER_TARGET_SERVER_INFORMATION]，
     * 收到的数据是直接通过SocketChannel.read 一个 ByteBuffer.allocate(1024)，
     * 暂时没有判断读取的数据长度是否满足能够完全拿到app发来的SOCKS5握手请求数据
     */
    fun handshakeOrForwardRequest(readBuffer: ByteBuffer, bytesRead: Int) {
        when (socks5HandshakeStatus) {
            ASK_ABOUT_AUTHENTICATION_METHOD -> {
                handshake1(readBuffer)
                socks5HandshakeStatus = TRANSFER_TARGET_SERVER_INFORMATION
            }

            TRANSFER_TARGET_SERVER_INFORMATION -> {
                handshake2(readBuffer)
                socks5HandshakeStatus = START_FORWARDING_REQUEST_DATA
            }

            START_FORWARDING_REQUEST_DATA -> {
                val byteArray = ByteArray(bytesRead)
                readBuffer.get(byteArray)
                forwardRequestToServer(bytesRead, byteArray)
            }
        }
    }

    /**
     * Socks 5 认证 第一阶段 客户端询问认证方式
     */
    private fun handshake1(readBuffer: ByteBuffer) {
        // 读取请求数据
        val socksVersion = readBuffer.get()
        val socksMethodsCount = readBuffer.get()
        val socksMethods = readBuffer.get(ByteArray(socksMethodsCount.toInt()))
        // 响应
        clientChannel.write(ByteBuffer.wrap(byteArrayOf(0x05, 0x00)))
    }

    /**
     * Socks 5 认证 第二阶段 传输目标服务器信息
     */
    private fun handshake2(readBuffer: ByteBuffer) {
        val socksVersion = readBuffer.get()
        val socksCommand = readBuffer.get()
        if (socksCommand.toInt() != 0x01) {
            LOGGER.error("仅支持CONNECT请求")
            clientChannel.close()
            return
        }
        readBuffer.get()
        remoteAddressTypeByte = readBuffer.get()
        when (remoteAddressTypeByte.toInt()) {
            0x01 -> {
                // IPv4地址
                val byteArray = ByteArray(4)
                readBuffer.get(byteArray)
                remoteAddressBytes = byteArray
                remoteAddress =
                    remoteAddressBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }

            0x03 -> {
                // 域名
                val domainLengthByte = readBuffer.get()
                val byteArray = ByteArray(domainLengthByte.toInt())
                readBuffer.get(byteArray)
                remoteAddress = String(byteArray)
                remoteAddressBytes = byteArrayOf(domainLengthByte) + byteArray
            }

            0x04 -> {
                // IPv6地址
                val byteArray = ByteArray(16)
                readBuffer.get(byteArray)
                remoteAddressBytes = byteArray
                remoteAddress =
                    remoteAddressBytes.joinToString(":") {
                        (it.toInt() and 0xFF).toString(
                            16
                        )
                    }
            }
        }
        val byteArray = ByteArray(2)
        readBuffer.get(byteArray)
        remotePortBytes = byteArray
        remotePort = ByteBuffer.wrap(remotePortBytes).getShort()
        LOGGER.info("[$appId]要访问的目标服务器[$remoteAddressTypeByte][$remoteAddress:$remotePort]")
        // ----------------------------------------
        val addressBytes = remoteAddress.toByteArray()
        registerToProxyServerManager()
        forwardRequestToServer(
            Packet(
                appId,
                ProxyInstruction.CONNECT.instructionId,
                addressBytes.size + remotePortBytes.size,
                addressBytes + remotePortBytes
            ).toByteArray()
        )
    }

    override fun targetServerCanBeConnectedCallback() {
        scope.run {
            runCatching {
                clientChannel.write(
                    ByteBuffer.wrap(
                        byteArrayOf(
                            0x05, //版本号
                            0x00, //代理服务器连接目标服务器成功
                            0x00,
                            remoteAddressTypeByte
                        ) + remoteAddressBytes + remotePortBytes
                    )
                )
            }.onFailure {
                LOGGER.warn("响应[$appId]目标服务器能够连接，Socks 5握手成功时遇到错误[${it.message}]")
            }
        }
    }

    /**
     * 发送请求数据到代理服务端
     */
    private fun forwardRequestToServer(bytesRead: Int, data: ByteArray) {
        forwardRequestToServer(Packet(appId, ProxyInstruction.SEND.instructionId, bytesRead, data).toByteArray())
    }

    /**
     * 通知代理服务端请求已经结束
     */
    fun notifyProxyServerRequestHasEnded(isCloseNormally: Boolean = true) {
        if (isCloseNormally) {
            LOGGER.info("[${getId()}]请求正常结束")
        }
        forwardRequestToServer(
            Packet(
                appId,
                if (isCloseNormally) ProxyInstruction.DISCONNECT.instructionId else ProxyInstruction.EXCEPTION_DISCONNECT.instructionId,
                0,
                byteArrayOf()
            ).toByteArray()
        )
    }

    override fun shutdownGracefully() {
        scope.launch {
            super.shutdownGracefully()
            runCatching {
                clientChannel.close()
            }
        }
    }

    override fun shutdownAbnormally() {
        scope.launch {
            super.shutdownAbnormally()
            runCatching {
                clientChannel.close()
            }
        }
    }
}