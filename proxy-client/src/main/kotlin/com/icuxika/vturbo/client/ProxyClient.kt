package com.icuxika.vturbo.client

import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class ProxyClient {

    private lateinit var serverSocket: ServerSocket

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        println("CoroutineExceptionHandler got $exception")
    }

    fun launchServer(port: Int) {
        LOGGER.info("服务启动端口：$port")
        serverSocket = ServerSocket(port)

        runBlocking {
            while (true) {
                val client = serverSocket.accept()

                val supervisor = SupervisorJob()
                with(CoroutineScope(Dispatchers.IO + supervisor)) {
                    launch(exceptionHandler) {
                        val clientInput = client.getInputStream()
                        val clientOutput = client.getOutputStream()

                        // Socks 5 流程
                        // 1.客户端与服务器身份验证
                        val socksVersion = clientInput.read()
                        LOGGER.info("Socks版本：$socksVersion")
                        val nMethods = clientInput.read()
                        LOGGER.info("客户端支持的认证方式数量：$nMethods")
                        val methods = ByteArray(nMethods)
                        clientInput.read(methods)

                        // 2.代理服务器响应客户端请求
                        clientOutput.write(byteArrayOf(0x05, 0x00)) // Socks 5，不加密

                        // 3.客户端向代理服务器发送请求地址
                        val ver2 = clientInput.read()
                        val command = clientInput.read()
                        LOGGER.info("命令码：$command")
                        val rsv = clientInput.read()
                        if (command != 0x01) {
                            client.close()
                            return@launch
                        }

                        val remoteAddressType = clientInput.read()
                        val remoteAddress: String
                        val remoteAddressLength: Int
                        when (remoteAddressType) {
                            0x01 -> {
                                LOGGER.info("IPv4地址")
                                val addressBytes = ByteArray(4)
                                clientInput.read(addressBytes)
                                remoteAddress = addressBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                                remoteAddressLength = 4
                            }

                            0x03 -> {
                                LOGGER.info("域名")
                                val domainLength = clientInput.read()
                                val domainBytes = ByteArray(domainLength)
                                clientInput.read(domainBytes)
                                remoteAddress = String(domainBytes)
                                remoteAddressLength = domainLength
                            }

                            0x04 -> {
                                LOGGER.info("IPv6地址")
                                val addressBytes = ByteArray(16)
                                clientInput.read(addressBytes)
                                remoteAddress = addressBytes.joinToString(":") { (it.toInt() and 0xFF).toString(16) }
                                remoteAddressLength = 16
                            }

                            else -> {
                                client.close()
                                return@launch
                            }
                        }

                        val portBytes = ByteArray(2)
                        clientInput.read(portBytes)
                        val remotePort = ByteBuffer.wrap(portBytes).getShort().toInt()
                        LOGGER.info("目标服务器地址：$remoteAddress，目标服务器端口：$remotePort")

                        // 4.代理服务器响应客户端请求（代理与远程服务器建立链接，代理服务器相应客户端请求）
                        clientOutput.write(
                            byteArrayOf(
                                0x05, //版本号
                                0x00, //响应码：成功
                                0x00, //版本号
                                remoteAddressType.toByte(), // 目标服务器地址,
                                0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                            )
                        )

                        val remoteAddressBytes = when (remoteAddressType) {
                            0x01 -> remoteAddress.split(".").map { it.toInt().toByte() }.toByteArray()
                            0x03 -> byteArrayOf(remoteAddressLength.toByte()) + remoteAddress.toByteArray()
                            0x04 -> remoteAddress.split(":").map { it.toInt(16).toByte() }.toByteArray()
                            else -> byteArrayOf()
                        }

                        // TEST 访问目标服务器
                        val remoteSocket = Socket(remoteAddress, remotePort)
                        LOGGER.info("remoteSocket.isConnected：${remoteSocket.isConnected}")
                        val remoteInput = remoteSocket.getInputStream()
                        val remoteOutput = remoteSocket.getOutputStream()
                        val buffer = ByteArray(1024)
                        var bytesRead: Int

                        launch {
                            val dataStream = ByteArrayOutputStream()
                            LOGGER.info("转发客户端请求数据到目标服务器")
                            while (true) {
                                bytesRead = clientInput.read(buffer)
                                if (bytesRead == -1) {
                                    break
                                }
                                remoteOutput.write(buffer, 0, bytesRead)
                                dataStream.write(buffer, 0, bytesRead)
                            }
                            val x = dataStream.toByteArray()
                            LOGGER.info("客户端请求：\n${x.toString(Charsets.UTF_8)}")
                        }

                        LOGGER.info("返回目标服务器响应数据到客户端")
                        val dataStream = ByteArrayOutputStream()
                        while (true) {
                            bytesRead = remoteInput.read(buffer)
                            if (bytesRead == -1) {
                                break
                            }
                            clientOutput.write(buffer, 0, bytesRead)
                            dataStream.write(buffer, 0, bytesRead)
                        }
                        val x = dataStream.toByteArray()
                        LOGGER.info("目标服务器响应：\n${x.toString(Charsets.UTF_8)}")

                        remoteSocket.close()
                        client.close()
                    }
                }
            }
        }
    }

    fun test() {
        val remoteSocket = Socket("127.0.0.1", 8882)
        val remoteInput = remoteSocket.getInputStream()
        val remoteOutput = remoteSocket.getOutputStream()

        runBlocking {
            val hostBytes = "110.242.68.66".split(".").map { it.toInt().toByte() }.toByteArray()

            val portBuffer = ByteBuffer.allocate(2)
            portBuffer.putShort(80)
            val portBytes = portBuffer.array()

            remoteOutput.write(
                Packet(
                    1,
                    ProxyInstruction.CONNECT.instructionId,
                    6,
                    hostBytes + portBytes
                ).toByteArray()
            )
            remoteOutput.flush()

            val buffer = ByteArray(1024)
            val bytesRead: Int = remoteInput.read(buffer)
            if (bytesRead != 12) {
                LOGGER.error("代理服务端返回的数据长度错误")
                return@runBlocking
            }
            val responsePacket = buffer.sliceArray(0 until bytesRead).toPacket()
            if (responsePacket.instructionId != ProxyInstruction.CONNECT.instructionId) {
                LOGGER.error("与目标服务器的连接建立失败")
                return@runBlocking
            }

            // 对于socks部分，此处应响应连接成功

            val text = "GET /baidu.html HTTP/1.1\r\nHost: www.baidu.com\r\nConnection: Close\r\n\r\n"
            val textBytes = text.toByteArray()
            LOGGER.info("size->${textBytes.size}")

            remoteOutput.write(Packet(1, ProxyInstruction.SEND.instructionId, textBytes.size, textBytes).toByteArray())
            remoteOutput.flush()

            var isFirst = true
            while (true) {
                remoteInput.readCompletePacket(LOGGER)?.let { packet ->
                    LOGGER.info((if (isFirst) "\n" else "") + String(packet.data))
                    if (isFirst) isFirst = false
                }
            }
        }
    }

    companion object {
        val LOGGER = logger()
    }
}