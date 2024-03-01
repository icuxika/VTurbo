package com.icuxika.vturbo.commons.encrypt

import java.security.AlgorithmParameters
import java.security.Key
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


object AESCoder {

    /**
     * 密钥算法
     */
    private const val KEY_ALGORITHM = "AES"

    /**
     * 加密/解密算法 /工作模式 /填充方式
     */
    private const val CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding"

    /**
     * 转换密钥
     */
    private fun toKey(key: ByteArray): Key = SecretKeySpec(key, KEY_ALGORITHM)

    /**
     * 解密
     */
    fun decrypt(data: ByteArray?, key: ByteArray, iv: ByteArray): ByteArray {
        val k = toKey(key)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, k, generateIV(iv))
        return cipher.doFinal(data)
    }

    /**
     * 加密
     */
    fun encrypt(data: ByteArray?, key: ByteArray, iv: ByteArray): ByteArray {
        val k = toKey(key)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, k, generateIV(iv))
        return cipher.doFinal(data)
    }

    /**
     * 生成 iv
     */
    private fun generateIV(iv: ByteArray): AlgorithmParameters {
        val parameters = AlgorithmParameters.getInstance(KEY_ALGORITHM)
        parameters.init(IvParameterSpec(iv))
        return parameters
    }
}