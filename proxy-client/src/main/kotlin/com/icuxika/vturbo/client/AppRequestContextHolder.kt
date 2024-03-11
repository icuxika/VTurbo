package com.icuxika.vturbo.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket

class AppRequestContextHolder(
    private val scope: CoroutineScope,
    private val proxyServerManager: ProxyServerManager,
    private val client: Socket,
    val appId: Int
) {
    private val clientInput = client.getInputStream()
    private val clientOutput = client.getOutputStream()

    private var remoteAddressType: Int = 0
    private lateinit var remoteAddressBytesForSocks5Response: ByteArray
    private lateinit var remotePortBytes: ByteArray

    fun startRequestProxy() {
        scope.launch {
            // ----------Socks 5 验证----------
            // 1.app与服务器身份验证
            var socksVersion = clientInput.read()
            val socksMethodsCount = clientInput.read()
            val socksMethods = ByteArray(socksMethodsCount)
            clientInput.read(socksMethods)
            // 2.响应app，选择握手方式
            sendRequestDataToApp(byteArrayOf(0x05, 0x00), AppSocketStatus.ON_CONNECTING)
            // 3.app发送目标服务器的信息
            socksVersion = clientInput.read()
            val socksCommand = clientInput.read()
            if (socksCommand != 0x01) {
                LOGGER.info("仅支持Connect请求")
                client.close()
                return@launch
            }
            val socksRSV = clientInput.read()

            remoteAddressType = clientInput.read()
            val remoteAddress: String
            when (remoteAddressType) {
                0x01 -> {
                    // IPv4
                    val addressBytes = ByteArray(4)
                    clientInput.read(addressBytes)
                    remoteAddress = addressBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    remoteAddressBytesForSocks5Response = addressBytes
                }

                0x03 -> {
                    // 域名地址，第一个字节指定长度
                    val domainLength = clientInput.read()
                    val domainBytes = ByteArray(domainLength)
                    clientInput.read(domainBytes)
                    remoteAddress = String(domainBytes)
                    remoteAddressBytesForSocks5Response = byteArrayOf(domainLength.toByte()) + domainBytes
                }

                0x04 -> {
                    // IPv6
                    val addressBytes = ByteArray(16)
                    clientInput.read(addressBytes)
                    remoteAddress = addressBytes.joinToString(":") { (it.toInt() and 0xFF).toString(16) }
                    remoteAddressBytesForSocks5Response = addressBytes
                }

                else -> {
                    LOGGER.error("目标服务器地址类型数据异常")
                    client.close()
                    return@launch
                }
            }
            remotePortBytes = ByteArray(2)
            clientInput.read(remotePortBytes)
            // ----------Socks 5 验证----------

            val remoteAddressBytes = remoteAddress.toByteArray()

            // 向Manager注册当前对象
            proxyServerManager.registerAppRequest(this@AppRequestContextHolder)

            // 发送目标服务器信息到代理服务器
            val result = sendRequestDataToProxyServer(
                appId,
                Packet(
                    appId,
                    ProxyInstruction.CONNECT.instructionId,
                    remoteAddressBytes.size + remotePortBytes.size,
                    remoteAddressBytes + remotePortBytes
                ).toByteArray()
            )
            if (!result) {
                // 向代理服务器转发请求数据失败
                proxyServerManager.unregisterAppRequest(this@AppRequestContextHolder)
                sendRequestDataToApp(
                    byteArrayOf(
                        0x05, //版本号
                        0x01, // 代理服务器故障
                        0x00,
                        remoteAddressType.toByte(),
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                    ),
                    AppSocketStatus.ON_DISCONNECTED
                )
            }
        }
    }

    /**
     * 代理服务器能够与目标服务器建立连接，通知app，开启死循环转发app的请求数据到代理服务器
     */
    fun afterHandshake() {
        scope.launch {
            LOGGER.info("与目标服务器成功建立连接，通过Socks 5协议通知app[$appId]")
            // 与目标服务器建立连接成功，回应app
            sendRequestDataToApp(
                byteArrayOf(
                    0x05, //版本号
                    0x00, //代理服务器连接目标服务器成功
                    0x00,
                    remoteAddressType.toByte()
                ) + remoteAddressBytesForSocks5Response + remotePortBytes,
                AppSocketStatus.ON_CONNECTED
            )

            // 转发app的请求数据到代理服务端
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (true) {
                try {
                    bytesRead = clientInput.read(buffer)
                    if (bytesRead > 0) {
                        sendRequestDataToProxyServer(
                            appId,
                            Packet(
                                appId,
                                ProxyInstruction.SEND.instructionId,
                                bytesRead,
                                buffer.sliceArray(0 until bytesRead)
                            ).toByteArray()
                        )
                    } else if (bytesRead == -1) {
                        break
                    }
                } catch (e: IOException) {
                    // 此处异常处理针对 InputStream.read
                    // sendRequestDataToProxyClient 内部异常内部处理
                    LOGGER.warn("app[$appId]关闭了Socket连接")
                    client.close()
                    break
                }
            }
            closeAppSocket()
        }
    }

    /**
     * 发送数据到代理服务器
     */
    private suspend fun sendRequestDataToProxyServer(appId: Int, data: ByteArray): Boolean {
        return try {
            proxyServerManager.sendRequestDataToProxyServer(appId, data)
        } catch (e: Exception) {
            LOGGER.error("转发app[$appId]的请求数据到代理服务器时发生错误[${e.message}]")
            false
        }
    }

    /**
     * 发送数据到app
     */
    suspend fun sendRequestDataToApp(
        data: ByteArray,
        appSocketStatus: AppSocketStatus = AppSocketStatus.ON_FORWARDING
    ) {
        try {
            if (client.isConnected && !client.isClosed) {
                withContext(Dispatchers.IO) {
                    clientOutput.write(data)
                }
            }
        } catch (e: Exception) {
            LOGGER.warn("[${appSocketStatus.status}]向app[$appId]转发请求数据时发生了错误[${e.message}]")
            sendRequestDataToProxyServer(
                appId, Packet(
                    appId,
                    ProxyInstruction.DISCONNECT.instructionId,
                    0,
                    byteArrayOf()
                ).toByteArray()
            )
        }
    }

    /**
     * 关闭和app之间Socket连接
     */
    fun closeAppSocket() {
        try {
            client.close()
        } catch (e: Exception) {
            LOGGER.warn("关闭app[$appId]的Socket时发生错误[${e.message}]")
        }
    }

    companion object {
        val LOGGER = logger()
    }
}