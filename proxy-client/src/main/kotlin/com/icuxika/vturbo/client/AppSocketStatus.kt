package com.icuxika.vturbo.client

/**
 * App的Socket状态
 */
enum class AppSocketStatus(val status: String) {
    ON_CONNECTING("正在建立连接"),

    ON_CONNECTED("已经建立连接"),

    ON_FORWARDING("正在进行请求转发"),

    ON_DISCONNECTED("连接断开")
}