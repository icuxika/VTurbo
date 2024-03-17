package com.icuxika.vturbo.client.protocol.bio

import com.icuxika.vturbo.client.protocol.NAbstractProtocolHandle
import com.icuxika.vturbo.client.server.ProxyServerManager
import com.icuxika.vturbo.commons.extensions.isConnecting
import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.IO_READ_BUFFER_SIZE
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.Socket
import java.nio.ByteBuffer

class AppRequestContextHolder(
    private val client: Socket,
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
        startHandshake()
    }

    override suspend fun forwardRequestToApp(data: ByteArray) {
        runCatching {
            if (!client.isConnecting()) {
                client.getOutputStream().write(data)
            }
        }.onFailure {
            LOGGER.warn("发送数据到app channel时出现了错误[${it.message}]")
            shutdownAbnormally()
        }
    }

    override fun startHandshake() {
        scope.launch {
            val dataInputStream = DataInputStream(client.getInputStream())
            // ----------------------------------------
            var socksVersion = dataInputStream.readByte()
            val socksMethodsCount = dataInputStream.readByte()
            val socksMethods = dataInputStream.readNBytes(socksMethodsCount.toInt())
            // ----------------------------------------
            forwardRequestToApp(byteArrayOf(0x05, 0x00))
            // ----------------------------------------
            socksVersion = dataInputStream.readByte()
            val socksCommand = dataInputStream.readByte()
            if (socksCommand.toInt() != 0x01) {
                LOGGER.error("仅支持CONNECT请求")
                shutdownGracefully()
                return@launch
            }
            dataInputStream.readByte()
            remoteAddressTypeByte = dataInputStream.readByte()
            when (remoteAddressTypeByte.toInt()) {
                0x01 -> {
                    // IPv4地址
                    remoteAddressBytes = dataInputStream.readNBytes(4)
                    remoteAddress = remoteAddressBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }

                0x03 -> {
                    // 域名
                    val domainLengthByte = dataInputStream.readByte()
                    val domainBytes = dataInputStream.readNBytes(domainLengthByte.toInt())
                    remoteAddress = String(domainBytes)
                    remoteAddressBytes = byteArrayOf(domainLengthByte) + domainBytes
                }

                0x04 -> {
                    // IPv6地址
                    remoteAddressBytes = dataInputStream.readNBytes(16)
                    remoteAddress = remoteAddressBytes.joinToString(":") { (it.toInt() and 0xFF).toString(16) }
                }

                else -> {
                    LOGGER.error("不支持的目标服务器地址类型")
                    shutdownGracefully()
                    return@launch
                }
            }
            remotePortBytes = dataInputStream.readNBytes(2)
            remotePort = ByteBuffer.wrap(remotePortBytes).getShort()
            // ----------------------------------------
            // 向 proxyServerManager 注册自身对象
            registerToProxyServerManager()
            // 将app要访问的目标服务器信息发送到代理服务器
            // 发送的目标服务器地址是经过处理的，包含.:
            val addressBytes = remoteAddress.toByteArray()
            forwardRequestToServer(
                Packet(
                    appId,
                    ProxyInstruction.CONNECT.instructionId,
                    addressBytes.size + remotePortBytes.size,
                    addressBytes + remotePortBytes
                ).toByteArray()
            )
        }
    }

    override fun afterHandshake() {
        scope.launch {
            LOGGER.info("app[$appId]要访问的目标服务器[$remoteAddress:$remotePort]可以连接")

            // 与目标服务器成功建立连接，通知app
            // 此处响应数据后三部分要与前面握手阶段一致，否则浏览器会断开连接
            forwardRequestToApp(
                byteArrayOf(
                    0x05, //版本号
                    0x00, //代理服务器连接目标服务器成功
                    0x00,
                    remoteAddressTypeByte
                ) + remoteAddressBytes + remotePortBytes
            )

            withContext(Dispatchers.IO) {
                runCatching {
                    val buffer = ByteArray(IO_READ_BUFFER_SIZE)
                    var bytesRead: Int
                    while (client.getInputStream().read(buffer).also { bytesRead = it } != -1) {
                        forwardRequestToServer(
                            Packet(
                                appId,
                                ProxyInstruction.SEND.instructionId,
                                bytesRead,
                                buffer.sliceArray(0 until bytesRead)
                            ).toByteArray()
                        )
                    }
                    // 请求结束
                    forwardRequestToServer(
                        Packet(
                            appId,
                            ProxyInstruction.DISCONNECT.instructionId,
                            0,
                            byteArrayOf()
                        ).toByteArray()
                    )
                    shutdownGracefully()
                }.onFailure {
                    LOGGER.warn("app[$appId]关闭了socket")
                    shutdownAbnormally()
                }
            }
        }
    }

    override fun shutdownGracefully() {
        super.shutdownGracefully()
        runCatching {
            client.close()
        }
    }

    override fun shutdownAbnormally() {
        super.shutdownAbnormally()
        runCatching {
            client.close()
        }
    }

    companion object {
        val LOGGER = logger()
    }
}