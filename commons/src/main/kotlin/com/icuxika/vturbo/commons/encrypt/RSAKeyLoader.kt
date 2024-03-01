package com.icuxika.vturbo.commons.encrypt

import java.nio.file.Files
import java.nio.file.Paths

interface RSAKeyLoader {
    /**
     * 加载 RSA 公钥
     */
    fun loadRSAPublicKey(): String

    /**
     * 加载 RSA 私钥
     */
    fun loadRSAPrivateKey(): String
}

class PemRSAKeyLoader : RSAKeyLoader {

    private val directory: String = System.getProperty("keys.path")
    private val publicKeyFilePath: String = "${directory}rsa_public_key.pem"
    private val privateKeyFilePath: String = "${directory}rsa_private_key.pem"

    private val publicKey: String by lazy {
        StringBuilder().apply {
            Files.lines(Paths.get(publicKeyFilePath)).forEach { line ->
                if (!line.contains("-----BEGIN PUBLIC KEY-----") && !line.contains("-----END PUBLIC KEY-----")) {
                    this.append(line)
                }
            }
        }.toString()
    }

    private val privateKey: String by lazy {
        StringBuilder().apply {
            Files.lines(Paths.get(privateKeyFilePath)).forEach { line ->
                if (!line.contains("-----BEGIN PRIVATE KEY-----") && !line.contains("-----END PRIVATE KEY-----")) {
                    this.append(line)
                }
            }
        }.toString()
    }

    override fun loadRSAPublicKey(): String = publicKey

    override fun loadRSAPrivateKey(): String = privateKey

}