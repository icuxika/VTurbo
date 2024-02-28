package com.icuxika.vturbo.commons.tcp

import java.io.DataOutput
import java.nio.ByteBuffer

/**
 * 指令对象
 */
class Instruction(val instructionId: Int, val sendPacket: Packet, val receivePacket: Packet) {

    fun send(output: DataOutput) {}

    fun receive(byteBuffer: ByteBuffer) {}
}

/**
 * 代理指令
 */
enum class ProxyInstruction(val instructionId: Int) {
    /**
     * 建立连接
     */
    CONNECT(1),

    /**
     * 发送数据
     */
    SEND(2),

    /**
     * 回应数据
     */
    RESPONSE(3),

    /**
     * 连接结束
     */
    DISCONNECT(4)
}