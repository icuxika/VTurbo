package com.icuxika.vturbo.commons.encrypt

object EncryptionUtil {
    val encryptionOnPublicRSA = EncryptionFactory.createEncryptionAlgorithm(EncryptionAlgorithm.RSA_PUBLIC_ENC)
    val encryptionOnPrivateRSA = EncryptionFactory.createEncryptionAlgorithm(EncryptionAlgorithm.RSA_PRIVATE_ENC)
}