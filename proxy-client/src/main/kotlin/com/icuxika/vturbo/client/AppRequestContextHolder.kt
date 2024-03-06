package com.icuxika.vturbo.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.ProxyInstruction
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket

class AppRequestContextHolder(
    private val scope: CoroutineScope,
    private val proxyServerManager: ProxyServerManager,
    private val client: Socket,
    val appId: Int
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
        LOGGER.error("协程运行时捕获到异常 $exception")
    }

    private val clientInput = client.getInputStream()
    private val clientOutput = client.getOutputStream()

    private var remoteAddressType: Int = 0

    fun startRequestProxy() {
        scope.launch(exceptionHandler) {
            // ----------Socks 5 验证----------
            // 1.app与服务器身份验证
            var socksVersion = clientInput.read()
            val socksMethodsCount = clientInput.read()
            val socksMethods = ByteArray(socksMethodsCount)
            clientInput.read(socksMethods)
            // 2.响应app，选择握手方式
            sendRequestDataToApp(byteArrayOf(0x05, 0x00))
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
                }

                0x03 -> {
                    // 域名地址，第一个字节指定长度
                    val domainLength = clientInput.read()
                    val domainBytes = ByteArray(domainLength)
                    clientInput.read(domainBytes)
                    remoteAddress = String(domainBytes)
                }

                0x04 -> {
                    // IPv6
                    val addressBytes = ByteArray(16)
                    clientInput.read(addressBytes)
                    remoteAddress = addressBytes.joinToString(":") { (it.toInt() and 0xFF).toString(16) }
                }

                else -> {
                    LOGGER.error("目标服务器地址类型数据异常")
                    client.close()
                    return@launch
                }
            }
            val remotePortBytes = ByteArray(2)
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
                    )
                )
            }
        }
    }

    /**
     * 代理服务器能够与目标服务器建立连接，通知app，开启死循环转发app的请求数据到代理服务器
     */
    fun afterHandshake() {
        scope.launch(exceptionHandler) {
            LOGGER.info("与目标服务器成功建立连接，通过Socks 5协议通知app")
            // 与目标服务器建立连接成功，回应app
            sendRequestDataToApp(
                byteArrayOf(
                    0x05, //版本号
                    0x00, //代理服务器连接目标服务器成功
                    0x00,
                    remoteAddressType.toByte(),
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                )
            )

            // 转发app的请求数据到代理服务端
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (true) {
                try {
                    bytesRead = clientInput.read(buffer)
                    if (bytesRead != -1) {
                        sendRequestDataToProxyServer(
                            appId,
                            Packet(
                                appId,
                                ProxyInstruction.SEND.instructionId,
                                bytesRead,
                                buffer.sliceArray(0 until bytesRead)
                            ).toByteArray()
                        )
                    }
                } catch (e: IOException) {
                    LOGGER.error("转发app[$appId]的请求数据到代理服务器时发生错误->${e.message}")
                    break
                }
            }
        }
    }

    /**
     * 发送数据到代理服务器
     */
    private suspend fun sendRequestDataToProxyServer(appId: Int, data: ByteArray): Boolean {
        return proxyServerManager.sendRequestDataToProxyServer(appId, data)
    }

    /**
     * 发送数据到app
     */
    fun sendRequestDataToApp(data: ByteArray) {
        clientOutput.write(data)
    }

    /**
     * 关闭和app之间Socket连接
     */
    fun closeAppSocket() {
        client.close()
    }

    companion object {
        val LOGGER = logger()
    }
}