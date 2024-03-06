package com.icuxika.vturbo.commons.encrypt

interface AESKeyLoader {

    fun loadKey(): String

    fun loadIv(): String
}

class StrAESKeyLoader : AESKeyLoader {
    override fun loadKey(): String = "HVMCPlH7IaWOc7EzUDV5SUm+85A1dgMi"

    override fun loadIv(): String = "cJrgd6Brtybbp1WV"
}