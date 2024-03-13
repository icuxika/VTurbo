package com.icuxika.vturbo.client.protocol

import com.icuxika.vturbo.client.AppSocketStatus
import com.icuxika.vturbo.client.server.ProxyServerManager
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

open class Socks5ProtocolHandle(
    proxyServerManager: ProxyServerManager,
    override val scope: CoroutineScope,
    override val client: Socket,
    override val appId: Int
) : AbstractProtocolHandle(proxyServerManager, scope, client, appId) {
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
    override fun startHandshake() {
        scope.launch {
            val dataInputStream = DataInputStream(client.getInputStream())
            // ----------------------------------------
            var socksVersion = dataInputStream.readByte()
            val socksMethodsCount = dataInputStream.readByte()
            val socksMethods = dataInputStream.readNBytes(socksMethodsCount.toInt())
            // ----------------------------------------
            forwardRequestToApp(byteArrayOf(0x05, 0x00), AppSocketStatus.ON_CONNECTING)
            // ----------------------------------------
            socksVersion = dataInputStream.readByte()
            val socksCommand = dataInputStream.readByte()
            if (socksCommand.toInt() != 0x01) {
                LOGGER.error("仅支持CONNECT请求")
                clean()
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
                    clean()
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
            if (!forwardRequestToServer(
                    Packet(
                        appId,
                        ProxyInstruction.CONNECT.instructionId,
                        addressBytes.size + remotePortBytes.size,
                        addressBytes + remotePortBytes
                    ).toByteArray()
                )
            ) {
                unregisterFromProxyServerManager()
                clean()
                return@launch
            }
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
                ) + remoteAddressBytes + remotePortBytes,
                AppSocketStatus.ON_CONNECTED
            )

            startForwardRequest()
        }
    }

    override suspend fun startForwardRequest() {
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
                clean()
            }.onFailure {
                LOGGER.warn("app[$appId]关闭了socket")
                clean()
            }
        }
    }

    companion object {
        val LOGGER = logger()
    }
}