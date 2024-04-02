package com.icuxika.vturbo.client.server

import com.icuxika.vturbo.client.protocol.ProtocolHandle

interface ProxyServer {

    /**
     * 转发app的请求数据到代理服务端
     */
    fun forwardRequestToProxyServer(data: ByteArray)

    /**
     * 注册[ProtocolHandle]到[AbstractProxyServer]
     */
    fun registerProtocolHandle(protocolHandle: ProtocolHandle)

    /**
     * 从[AbstractProxyServer]中取消注册[ProtocolHandle]
     */
    fun unregisterProtocolHandle(protocolHandle: ProtocolHandle)
}