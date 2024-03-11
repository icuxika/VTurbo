package com.icuxika.vturbo.commons.tcp

import com.icuxika.vturbo.commons.encrypt.EncryptionUtil
import org.slf4j.Logger
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * 二进制数据结构：
 *  appId|指令id|长度|数据
 */
data class Packet(val appId: Int, val instructionId: Int, val length: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet

        if (appId != other.appId) return false
        if (instructionId != other.instructionId) return false
        if (length != other.length) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = appId
        result = 31 * result + instructionId
        result = 31 * result + length
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * 将[Packet]的数据输出为[ByteArray]
 */
fun Packet.toByteArray(): ByteArray {
    if (length == 0) {
        val byteBuffer = ByteBuffer.allocate(Int.SIZE_BYTES * 3 + data.size)
        byteBuffer.putInt(appId)
        byteBuffer.putInt(instructionId)
        byteBuffer.putInt(length)
        byteBuffer.put(data)
        return byteBuffer.array()
    }
    val encryptedData = EncryptionUtil.aesEnc.encrypt(data)
    val byteBuffer = ByteBuffer.allocate(Int.SIZE_BYTES * 3 + encryptedData.size)
    byteBuffer.putInt(appId)
    byteBuffer.putInt(instructionId)
    byteBuffer.putInt(encryptedData.size)
    byteBuffer.put(encryptedData)
    return byteBuffer.array()
}

/**
 * 将[ByteArray]输出为[Packet]
 */
fun ByteArray.toPacket(): Packet {
    val byteBuffer = ByteBuffer.wrap(this)
    val appId = byteBuffer.getInt()
    val instructionId = byteBuffer.getInt()
    val length = byteBuffer.getInt()
    val data = ByteArray(byteBuffer.remaining())
    byteBuffer.get(data)
    return Packet(appId, instructionId, length, data)
}

/**
 * 从[InputStream]中读取一个到完整的[Packet]
 */
fun InputStream.readCompletePacket(logger: Logger): Packet {
    // TODO 当发生IO异常时候，断开对应的代理客户端与代理服务端之间的连接
    val dataInputStream = DataInputStream(this)
    val appId = dataInputStream.readInt()
    val instructionId = dataInputStream.readInt()
    val length = dataInputStream.readInt()
    if (length == 0) {
        return Packet(appId, instructionId, length, byteArrayOf())
    }
    val encryptedData = ByteArray(length)
    dataInputStream.readFully(encryptedData)
    val data = EncryptionUtil.aesEnc.decrypt(encryptedData)
    return Packet(appId, instructionId, data.size, data)
}

