package com.icuxika.vturbo.commons.encrypt

import java.util.*

interface Encryption {

    /**
     * 加密
     */
    fun encrypt(data: ByteArray): ByteArray

    /**
     * 解密
     */
    fun decrypt(data: ByteArray): ByteArray
}

enum class EncryptionAlgorithm {
    /**
     * RSA 公钥加密私钥解密
     */
    RSA_PUBLIC_ENC,

    /**
     * RSA 私钥加密公钥解密
     */
    RSA_PRIVATE_ENC,

    /**
     * AES 加密解密
     */
    AES
}

/**
 * RSA 公钥加密私钥解密实现
 */
class EncryptionOnRSAPublicEnc : Encryption, RSAKeyLoader by PemRSAKeyLoader() {
    override fun encrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.getMimeDecoder().decode(loadRSAPublicKey())
        return RSACoder.encryptByPublicKey(data, keyBytes)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.getMimeDecoder().decode(loadRSAPrivateKey())
        return RSACoder.decryptByPrivateKey(data, keyBytes)
    }
}

/**
 * RSA 私钥加密公钥解密实现
 */
class EncryptionOnRSAPrivateEnc : Encryption, RSAKeyLoader by PemRSAKeyLoader() {
    override fun encrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.getMimeDecoder().decode(loadRSAPrivateKey())
        return RSACoder.encryptByPrivateKey(data, keyBytes)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.getMimeDecoder().decode(loadRSAPublicKey())
        return RSACoder.decryptByPublicKey(data, keyBytes)
    }
}

/**
 * AES 加密解密实现
 */
class EncryptionOnAESEnc : Encryption, AESKeyLoader by StrAESKeyLoader() {
    override fun encrypt(data: ByteArray): ByteArray {
        val keyBytes = loadKey().toByteArray()
        val ivBytes = loadIv().toByteArray()
        return AESCoder.encrypt(data, keyBytes, ivBytes);
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val keyBytes = loadKey().toByteArray()
        val ivBytes = loadIv().toByteArray()
        return AESCoder.decrypt(data, keyBytes, ivBytes);
    }

}

object EncryptionFactory {
    fun createEncryptionAlgorithm(algorithm: EncryptionAlgorithm): Encryption {
        return when (algorithm) {
            EncryptionAlgorithm.RSA_PUBLIC_ENC -> EncryptionOnRSAPublicEnc()
            EncryptionAlgorithm.RSA_PRIVATE_ENC -> EncryptionOnRSAPrivateEnc()
            EncryptionAlgorithm.AES -> EncryptionOnAESEnc()
        }
    }
}
