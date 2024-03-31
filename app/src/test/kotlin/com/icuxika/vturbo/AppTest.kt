package com.icuxika.vturbo

import com.icuxika.vturbo.app.App
import com.icuxika.vturbo.commons.encrypt.EncryptionAlgorithm
import com.icuxika.vturbo.commons.encrypt.EncryptionFactory
import com.icuxika.vturbo.commons.tcp.NioPacketResolver
import com.icuxika.vturbo.commons.tcp.Packet
import com.icuxika.vturbo.commons.tcp.toByteArray
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertNotNull

class AppTest {
    @Test
    fun appHasAGreeting() {
        val classUnderTest = App()
        assertNotNull(classUnderTest.greeting, "app should have a greeting")
    }

    @Test
    fun encryptionOnRSA() {
        val text = "Hello, world!"
        val encryption = EncryptionFactory.createEncryptionAlgorithm(EncryptionAlgorithm.RSA_PRIVATE_ENC)
        val encryptedData = encryption.encrypt(text.toByteArray())
        val decryptedData = encryption.decrypt(encryptedData)
        println(String(decryptedData))
    }

    @Test
    fun encryptionOnAES() {
        val time = measureTimeMillis {
            val text = generateRandomString(1024 * 8)
            println("生成的随机字符串->$text")
            println("原文长度[${text.toByteArray().size}]")
            val encryption = EncryptionFactory.createEncryptionAlgorithm(EncryptionAlgorithm.AES)
            val encryptedData = encryption.encrypt(text.toByteArray())
            println("密文长度[${encryptedData.size}]")
            val decryptedData = encryption.decrypt(encryptedData)
            println("解密后的原文长度[${decryptedData.size}]")
            println(String(decryptedData))
        }
        println("程序执行时长[$time]")
    }

    private fun generateRandomString(length: Int): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length).map { chars.random() }.joinToString("")
    }

    @Test
    fun nioPacketRead() {
        val nioPacketResolver = NioPacketResolver { packet: Packet ->
            println(packet)
        }

        val data1 = "abc1234".toByteArray()
        nioPacketResolver.readPacket(Packet(1, 1, data1.size, data1).toByteArray())

        // 每次读到数据不足一个Packet
        val data2 = "abcdefghijklmnopqrstuvwxyz".toByteArray()
        val packet2 = Packet(1, 1, data2.size, data2).toByteArray()
        val packet2s1 = packet2.slice(0..<10).toByteArray()
        val packet2s2 = packet2.slice(10..<packet2.size).toByteArray()
        nioPacketResolver.readPacket(packet2s1)
        nioPacketResolver.readPacket(packet2s2)

        // 读到的数据超出一个Packet
        val data3 = "qwer1234".toByteArray()
        val packet3 = Packet(1, 1, data3.size, data3).toByteArray()
        val data4 = "asdf56789".toByteArray()
        val packet4 = Packet(1, 1, data4.size, data4).toByteArray()
        val packet4s1 = packet4.slice(0..<4).toByteArray()
        val packet4s2 = packet4.slice(4..<packet4.size).toByteArray()
        nioPacketResolver.readPacket(packet3 + packet4s1)
        nioPacketResolver.readPacket(packet4s2)
    }
}

