package com.icuxika.vturbo.client

import com.icuxika.vturbo.commons.extensions.logger
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.nio.ByteBuffer

class ProxyClient {

    fun test() {
        val remoteSocket = Socket("127.0.0.1", 8882)
        val remoteInput = remoteSocket.getInputStream()
        val remoteOutput = remoteSocket.getOutputStream()

        runBlocking {
            val hostBytes = "39.98.39.176".split(".").map { it.toInt().toByte() }.toByteArray()
            val portBuffer = ByteBuffer.allocate(2)
            portBuffer.putShort(8882)
            val portBytes = portBuffer.array()

            remoteOutput.write(hostBytes + portBytes)
            remoteOutput.flush()
            remoteSocket.close()
        }
    }

    companion object {
        val LOGGER = logger()
    }
}