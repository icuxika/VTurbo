package com.icuxika.vturbo.client.protocol

/**
 * 每一个App连接都对应一个单独的实现了[ProtocolHandle]接口的对象
 */
interface ProtocolHandle {
    /**
     * 每一个App连接分配一个唯一id
     */
    fun getId(): Int

    /**
     * 转发代理服务端的请求数据到Channel中
     */
    suspend fun forwardRequestToChannelOfApp(data: ByteArray)

    /**
     * App要访问的目标服务器可以连接，可以通知App Socks5握手成功
     */
    fun targetServerCanBeConnectedCallback()

    /**
     * 正常关闭
     */
    fun shutdownGracefully()

    /**
     * 异常关闭
     */
    fun shutdownAbnormally()
}