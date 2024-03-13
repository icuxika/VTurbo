package com.icuxika.vturbo.client.protocol

import com.icuxika.vturbo.client.AppSocketStatus

/**
 * 对于Socks 5协议验证主要分为三个阶段
 * 1. 验证Socks 5协议，检查目标服务器是否可以连接
 * 2. 目标服务器可以连接，通知app
 * 3. 转发请求数据
 */
interface ProtocolHandle {
    /**
     * Socks5 握手之前
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
     * 握手成功后，开始转发app的请求到代理服务器
     */
    suspend fun startForwardRequest()

    /**
     * 向ProxyServerManager注册
     */
    fun registerToProxyServerManager()

    /**
     * 从ProxyServerManager中取消注册
     */
    fun unregisterFromProxyServerManager()

    /**
     * 发送请求数据到代理服务器
     */
    suspend fun forwardRequestToServer(data: ByteArray): Boolean

    /**
     * 发送请求数据到App
     */
    suspend fun forwardRequestToApp(data: ByteArray, appSocketStatus: AppSocketStatus)

    /**
     * 关闭app socket和清理其他资源
     */
    fun clean()
}