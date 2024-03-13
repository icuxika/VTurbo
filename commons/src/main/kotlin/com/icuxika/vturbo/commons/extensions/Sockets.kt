package com.icuxika.vturbo.commons.extensions

import java.net.Socket

/**
 * 判断[Socket]是否还在连接中，并不准确
 */
fun Socket.isConnecting(): Boolean = isConnected && !isClosed