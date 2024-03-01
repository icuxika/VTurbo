package com.icuxika.vturbo.commons.encrypt

object EncryptionUtil {
    val rsaPublicEnc = EncryptionFactory.createEncryptionAlgorithm(EncryptionAlgorithm.RSA_PUBLIC_ENC)
    val rsaPrivateEnc = EncryptionFactory.createEncryptionAlgorithm(EncryptionAlgorithm.RSA_PRIVATE_ENC)
    val aesEnc = EncryptionFactory.createEncryptionAlgorithm(EncryptionAlgorithm.AES)
}