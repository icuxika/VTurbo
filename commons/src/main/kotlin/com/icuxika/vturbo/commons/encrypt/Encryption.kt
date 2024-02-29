package com.icuxika.vturbo.commons.encrypt

import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

interface Encryption {

    fun encrypt(data: ByteArray): ByteArray

    fun decrypt(data: ByteArray): ByteArray
}

enum class EncryptionAlgorithm {
    RSA_PUBLIC_ENC,
    RSA_PRIVATE_ENC
}

class EncryptionOnPublicRSA : Encryption {

    private val privateKey: String by lazy {
        val privateKeyPEM = StringBuilder()
        Files.lines(Paths.get("../keys/rsa_private_key.pem")).forEach { line ->
            if (!line.contains("-----BEGIN PRIVATE KEY-----") && !line.contains("-----END PRIVATE KEY-----")) {
                privateKeyPEM.append(line)
            }
        }
        privateKeyPEM.toString()
    }

    private val publicKey: String by lazy {
        val publicKeyPEM = StringBuilder()
        Files.lines(Paths.get("../keys/rsa_public_key.pem")).forEach { line ->
            if (!line.contains("-----BEGIN PUBLIC KEY-----") && !line.contains("-----END PUBLIC KEY-----")) {
                publicKeyPEM.append(line)
            }
        }
        publicKeyPEM.toString()
    }

    override fun encrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.getMimeDecoder().decode(publicKey)
        return RSACoder.encryptByPublicKey(data, keyBytes)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.getMimeDecoder().decode(privateKey)
        return RSACoder.decryptByPrivateKey(data, keyBytes)
    }
}

class EncryptionOnPrivateRSA : Encryption {

    private val privateKey: String by lazy {
        val privateKeyPEM = StringBuilder()
        Files.lines(Paths.get("C:\\Users\\icuxika\\Downloads\\keys\\rsa_private_key.pem")).forEach { line ->
            if (!line.contains("-----BEGIN PRIVATE KEY-----") && !line.contains("-----END PRIVATE KEY-----")) {
                privateKeyPEM.append(line)
            }
        }
        privateKeyPEM.toString()
    }

    private val publicKey: String by lazy {
        val publicKeyPEM = StringBuilder()
        Files.lines(Paths.get("C:\\Users\\icuxika\\Downloads\\keys\\rsa_public_key.pem")).forEach { line ->
            if (!line.contains("-----BEGIN PUBLIC KEY-----") && !line.contains("-----END PUBLIC KEY-----")) {
                publicKeyPEM.append(line)
            }
        }
        publicKeyPEM.toString()
    }

    override fun encrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.getMimeDecoder().decode(privateKey)
        return RSACoder.encryptByPrivateKey(data, keyBytes)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.getMimeDecoder().decode(publicKey)
        return RSACoder.decryptByPublicKey(data, keyBytes)
    }
}

object EncryptionFactory {
    fun createEncryptionAlgorithm(algorithm: EncryptionAlgorithm): Encryption {
        return when (algorithm) {
            EncryptionAlgorithm.RSA_PUBLIC_ENC -> EncryptionOnPublicRSA()
            EncryptionAlgorithm.RSA_PRIVATE_ENC -> EncryptionOnPrivateRSA()
        }
    }
}
