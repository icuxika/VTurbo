package com.icuxika.vturbo.commons.encrypt

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher


object RSACoder {

    private const val KEY_ALGORITHM: String = "RSA"

    /**
     * 私钥解密
     */
    fun decryptByPrivateKey(data: ByteArray, key: ByteArray): ByteArray {
        val pkcS8EncodedKeySpec = PKCS8EncodedKeySpec(key)
        val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
        val privateKey = keyFactory.generatePrivate(pkcS8EncodedKeySpec)
        val cipher = Cipher.getInstance(keyFactory.algorithm)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(data)
    }

    /**
     * 公钥加密
     */
    fun encryptByPublicKey(data: ByteArray, key: ByteArray): ByteArray {
        val x509EncodedKeySpec = X509EncodedKeySpec(key)
        val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
        val publicKey = keyFactory.generatePublic(x509EncodedKeySpec)
        val cipher = Cipher.getInstance(keyFactory.algorithm)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    /**
     * 公钥解密
     */
    fun decryptByPublicKey(data: ByteArray, key: ByteArray): ByteArray {
        val x509EncodedKeySpec = X509EncodedKeySpec(key)
        val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
        val publicKey = keyFactory.generatePublic(x509EncodedKeySpec)
        val cipher = Cipher.getInstance(keyFactory.algorithm)
        cipher.init(Cipher.DECRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    /**
     * 私钥加密
     */
    fun encryptByPrivateKey(data: ByteArray, key: ByteArray): ByteArray {
        val pkcS8EncodedKeySpec = PKCS8EncodedKeySpec(key)
        val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
        val privateKey = keyFactory.generatePrivate(pkcS8EncodedKeySpec)
        val cipher = Cipher.getInstance(keyFactory.algorithm)
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(data)
    }

}