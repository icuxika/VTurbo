package com.icuxika.vturbo.commons.tcp

/**
 * [Packet]传递的数据类型
 */
enum class ProxyInstruction(val instructionId: Int) {
    /**
     * 建立连接
     * 对于代理服务端来说，表示客户端有新的app正在进行Socks 5握手，此刻要判断目标服务器是否可以正常连接
     * 对于代理客户端来说，表示app要访问的目标服务器可以建立连接
     */
    CONNECT(1),

    /**
     * 发送数据
     * 对于代理服务端来说，转发此数据到目标服务器
     * 对于代理客户端来说，转发此数据到app
     */
    SEND(2),

    /**
     * 连接异常断开
     * 对于代理服务端来说，app与代理客户端的连接异常断开
     * 对于代理客户端来说，目标服务器与代理服务端的连接异常断开，不一定代表请求数据没有传输完成
     */
    EXCEPTION_DISCONNECT(3),

    /**
     * 连接结束
     * 对于代理服务端来说，代理客户端从app出读到了-1，连接正常结束
     * 对于代理客户端来说，代理服务端从目标服务器读到了-1，连接正常结束
     */
    DISCONNECT(4)
}