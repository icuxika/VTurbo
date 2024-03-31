package com.icuxika.vturbo.commons.tcp

import java.nio.ByteBuffer

/**
 * 使用NIO时读取自定义包结构数据，一个个[Packet]的字节数据通过[java.nio.channels.SocketChannel]有序传递，但每次读到的[ByteBuffer]不一定是完整的[Packet]数据
 */
class NioPacketResolver(private val packetHandle: (packet: Packet) -> Unit) {

    private var state = State.ZERO

    /**
     * 缓存未读满时一个Packet的字节内容
     */
    private val cachedBytes = arrayListOf<Byte>()

    /**
     * 已经读出了[Packet.length]
     */
    private var currentPacketDataLength = 0

    /**
     * 读出[Packet.appId]需要的字节数
     */
    private var appIdBytesRange = Int.SIZE_BYTES * 1

    /**
     * 读出[Packet.instructionId]需要的字节数
     */
    private var instructionIdBytesRange = Int.SIZE_BYTES * 2

    /**
     * 读出[Packet.length]需要的字节数
     */
    private var lengthBytesRange = Int.SIZE_BYTES * 3

    fun readPacket(bytes: ByteArray) {
        val size = bytes.size
        // bytesRead记录着上一次调用时cachedBytes存储的字节数
        val bytesRead = cachedBytes.size
        // 缓存中加入此次调用时传递的字节内容，因此bytesRead不代表当前缓存的字节内容的长度
        bytes.forEach { cachedBytes.add(it) }
        when (state) {
            State.ZERO -> {
                if (size < appIdBytesRange) {
                    state = State.ZERO
                } else if (size in appIdBytesRange..<instructionIdBytesRange) {
                    state = State.APP_ID_HAS_READ
                } else if (size in instructionIdBytesRange..<lengthBytesRange) {
                    state = State.INSTRUCTION_ID_HAS_READ
                } else {
                    currentPacketDataLength = getPacketDataLength()
                    if (size > lengthBytesRange + currentPacketDataLength) {
                        // 超出一个Packet
                        readExtra()
                    } else {
                        if (size == lengthBytesRange + currentPacketDataLength) {
                            // 刚好一个Packet
                            handlePacket(cachedBytes.toByteArray())
                            reset()
                        } else {
                            // 不足一个Packet
                            state = State.LENGTH_HAS_READ
                        }
                    }
                }
            }

            State.APP_ID_HAS_READ -> {
                if (size + bytesRead < instructionIdBytesRange) {
                    state = State.APP_ID_HAS_READ
                } else if (size + bytesRead < lengthBytesRange) {
                    state = State.INSTRUCTION_ID_HAS_READ
                } else {
                    currentPacketDataLength = getPacketDataLength()
                    if (size + bytesRead > lengthBytesRange + currentPacketDataLength) {
                        // 超出一个Packet
                        readExtra()
                    } else {
                        if (size + bytesRead == lengthBytesRange + currentPacketDataLength) {
                            // 刚好一个Packet
                            handlePacket(cachedBytes.toByteArray())
                            reset()
                        } else {
                            // 不足一个Packet
                            state = State.LENGTH_HAS_READ
                        }
                    }
                }
            }

            State.INSTRUCTION_ID_HAS_READ -> {
                if (size + bytesRead < lengthBytesRange) {
                    state = State.INSTRUCTION_ID_HAS_READ
                } else {
                    currentPacketDataLength = getPacketDataLength()
                    if (size + bytesRead > lengthBytesRange + currentPacketDataLength) {
                        // 超出一个Packet
                        readExtra()
                    } else {
                        if (size + bytesRead == lengthBytesRange + currentPacketDataLength) {
                            // 刚好一个Packet
                            handlePacket(cachedBytes.toByteArray())
                            reset()
                        } else {
                            // 不足一个Packet
                            state = State.LENGTH_HAS_READ
                        }
                    }
                }
            }

            State.LENGTH_HAS_READ -> {
                if (size + bytesRead < lengthBytesRange + currentPacketDataLength) {
                    state = State.LENGTH_HAS_READ
                } else {
                    if (size + bytesRead == lengthBytesRange + currentPacketDataLength) {
                        // 刚好一个Packet
                        handlePacket(cachedBytes.toByteArray())
                        reset()
                    } else {
                        // 超出一个Packet
                        readExtra()
                    }
                }
            }
        }
    }

    private fun readExtra() {
        // 直接处理一个Packet
        val onePacketByteList =
            cachedBytes.subList(0, lengthBytesRange + currentPacketDataLength)
        handlePacket(onePacketByteList.toByteArray())

        // 对额外的包的内容递归处理
        val extraPacketByteList =
            cachedBytes.subList(
                lengthBytesRange + currentPacketDataLength,
                cachedBytes.size
            ).toMutableList()

        // 初始化相关变量
        reset()
        readPacket(extraPacketByteList.toByteArray())
    }

    private fun handlePacket(byteArray: ByteArray) {
        packetHandle(byteArray.toPacket())
    }

    /**
     * [Packet.length]
     */
    private fun getPacketDataLength() =
        ByteBuffer.wrap(cachedBytes.subList(instructionIdBytesRange, lengthBytesRange).toByteArray()).getInt()

    private fun reset() {
        state = State.ZERO
        cachedBytes.clear()
    }

    enum class State {
        /**
         * 读到了数据但未读到完整的[Packet.appId]
         */
        ZERO,

        /**
         * 读出了[Packet.appId]但未读到完整的[Packet.instructionId]
         */
        APP_ID_HAS_READ,

        /**
         * 读出了[Packet.instructionId]但未读到完整的[Packet.length]
         */
        INSTRUCTION_ID_HAS_READ,

        /**
         * 读出了[Packet.length]但未读到完整的[Packet.data]
         */
        LENGTH_HAS_READ,
    }
}