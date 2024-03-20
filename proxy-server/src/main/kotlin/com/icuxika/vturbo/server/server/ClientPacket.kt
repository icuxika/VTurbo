package com.icuxika.vturbo.server.server

data class ClientPacket(val clientId: Int, val appId: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ClientPacket

        if (clientId != other.clientId) return false
        if (appId != other.appId) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = clientId
        result = 31 * result + appId
        result = 31 * result + data.contentHashCode()
        return result
    }
}
