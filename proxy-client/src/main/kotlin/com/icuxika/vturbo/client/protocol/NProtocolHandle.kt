package com.icuxika.vturbo.client.protocol

interface NProtocolHandle {
    /**
     * 预备任务
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

    suspend fun forwardRequestToChannelOfApp(data: ByteArray)

    /**
     * 实际转发请求到app的操作
     */
    suspend fun forwardRequestToApp(data: ByteArray)

    /**
     * 正常关闭
     */
    fun shutdownGracefully()

    /**
     * 异常关闭
     */
    fun shutdownAbnormally()
}