package com.icuxika.vturbo

import com.icuxika.vturbo.app.App
import com.icuxika.vturbo.commons.encrypt.EncryptionAlgorithm
import com.icuxika.vturbo.commons.encrypt.EncryptionFactory
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
        val text = "Hello, world!"
        val encryption = EncryptionFactory.createEncryptionAlgorithm(EncryptionAlgorithm.AES)
        val encryptedData = encryption.encrypt(text.toByteArray())
        val decryptedData = encryption.decrypt(encryptedData)
        println(String(decryptedData))
    }
}

