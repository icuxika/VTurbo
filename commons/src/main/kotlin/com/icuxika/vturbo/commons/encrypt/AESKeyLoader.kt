package com.icuxika.vturbo.commons.encrypt

interface AESKeyLoader {

    fun loadKey(): String

    fun loadIv(): String
}

class StrAESKeyLoader : AESKeyLoader {
    override fun loadKey(): String = "1234567890abcdef"

    override fun loadIv(): String = "1234567890abcdef"
}