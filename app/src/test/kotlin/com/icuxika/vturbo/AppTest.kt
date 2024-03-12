package com.icuxika.vturbo

import com.icuxika.vturbo.app.App
import com.icuxika.vturbo.commons.encrypt.EncryptionAlgorithm
import com.icuxika.vturbo.commons.encrypt.EncryptionFactory
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
}

