package com.icuxika.vturbo.client.protocol

interface NProtocolHandle {
    /**
     * Socks5 握手之前，初始化读取[NAbstractProtocolHandle.bytesToAppChannel]数据的协程
     */
    fun beforeHandshake()

    /**
     * 开始Socks5握手
     */
    fun startHandshake()

    /**
     * 握手成功
     */
    fun afterHandshake()

    /**
     * 向ProxyServerManager注册
     */
    fun registerToProxyServerManager()

    /**
     * 从ProxyServerManager中取消注册
     */
    fun unregisterFromProxyServerManager()

    /**
     * 转发请求到代理服务端
     */
    fun forwardRequestToServer(data: ByteArray)

    /**
     * [com.icuxika.vturbo.client.server.ProxyServerManager]调用此函数将请求数据推送到[NAbstractProtocolHandle.bytesToAppChannel]
     */
    suspend fun forwardRequestToChannelOfApp(data: ByteArray)

    /**
     * 实际转发请求到app的操作
     */
    fun forwardRequestToApp(data: ByteArray)

    fun clean()
}