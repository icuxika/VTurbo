package com.icuxika.vturbo.commons.tcp

import com.icuxika.vturbo.commons.encrypt.EncryptionUtil
import org.slf4j.Logger
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
    val encryptedData = EncryptionUtil.encryptionOnPublicRSA.encrypt(data)
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
fun InputStream.readCompletePacket(logger: Logger): Packet? {
    val buffer = ByteBuffer.allocate(1024)
    var totalBytesRead = 0
    // 最小包大小，不包含数据
    val baseBytesSize = Int.SIZE_BYTES * 3
    while (totalBytesRead < baseBytesSize) {
        val bytesRead = read(buffer.array(), totalBytesRead, baseBytesSize - totalBytesRead)
        if (bytesRead <= 0) {
            return null
        }
        totalBytesRead += bytesRead
    }

    buffer.position(totalBytesRead)
    buffer.flip()

    val appId = buffer.getInt()
    val instructionId = buffer.getInt()
    val length = buffer.getInt()

    if (length == 0) {
        return Packet(appId, instructionId, length, byteArrayOf())
    }

    // 读取数据部分
    val dataBuffer = ByteBuffer.allocate(length)
    totalBytesRead = 0
    while (totalBytesRead < length) {
        val bytesRead = read(dataBuffer.array(), totalBytesRead, length - totalBytesRead)
        if (bytesRead <= 0) {
            return null
        }
        totalBytesRead += bytesRead
    }

    val encryptedData = dataBuffer.array()
    val data = EncryptionUtil.encryptionOnPublicRSA.decrypt(encryptedData)
    return Packet(appId, instructionId, data.size, data)
}