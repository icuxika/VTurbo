package com.icuxika.vturbo.client.protocol

enum class Socks5HandshakeStatus(val status: String) {

    ASK_ABOUT_AUTHENTICATION_METHOD("客户端询问认证方式"),

    TRANSFER_TARGET_SERVER_INFORMATION("传输目标服务器信息"),

    START_FORWARDING_REQUEST_DATA("开始转发请求数据")
}