package com.icuxika.vturbo.client.protocol.nio

import com.icuxika.vturbo.client.NProxyClient
import com.icuxika.vturbo.client.protocol.NAbstractProtocolHandle
import com.icuxika.vturbo.client.server.ProxyServerManager
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.CoroutineScope
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

class NAppRequestContextHolder(
    private val clientChannel: SocketChannel,
    proxyServerManager: ProxyServerManager,
    override val scope: CoroutineScope,
    override val appId: Int
) : NAbstractProtocolHandle(proxyServerManager, scope, appId) {

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

    override fun forwardRequestToApp(data: ByteArray) {
        runCatching {
            clientChannel.write(ByteBuffer.wrap(data))
        }.onFailure {
            LOGGER.error("向app写入数据时遇到错误")
        }
    }

    /**
     * Socks 5 认证 第一阶段 客户端询问认证方式
     */
    fun handshake1(readBuffer: ByteBuffer) {
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
    fun handshake2(readBuffer: ByteBuffer) {
        val socksVersion = readBuffer.get()
        val socksCommand = readBuffer.get()
        if (socksCommand.toInt() != 0x01) {
            NProxyClient.LOGGER.error("仅支持CONNECT请求")
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
        NProxyClient.LOGGER.info("要访问的目标服务器[$remoteAddress:$remotePort]")
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

    override fun startHandshake() {
        // do nothing
    }

    override fun afterHandshake() {
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
    }

    /**
     * 发送请求数据到代理服务端
     */
    fun forwardRequestToServer(bytesRead: Int, data: ByteArray) {
        forwardRequestToServer(Packet(appId, ProxyInstruction.SEND.instructionId, bytesRead, data).toByteArray())
    }

    /**
     * 通知代理服务端请求已经结束
     */
    fun notifyProxyServerRequestHasEnded() {
        forwardRequestToServer(Packet(appId, ProxyInstruction.DISCONNECT.instructionId, 0, byteArrayOf()).toByteArray())
        clean()
    }

    override fun clean() {
        super.clean()
        runCatching {
            clientChannel.close()
        }
    }
}