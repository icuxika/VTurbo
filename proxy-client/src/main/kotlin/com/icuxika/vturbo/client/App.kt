package com.icuxika.vturbo.client

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default

fun main(args: Array<String>) {
    val parser = ArgParser("vturbo-client")
    val port by parser.option(ArgType.Int, shortName = "p", description = "Server port").default(8881)
    // 代理服务器的ip地址和端口
    val server by parser.option(ArgType.String, shortName = "s", description = "Proxy server address")
        .default("127.0.0.1:8882")
    parser.parse(args)

    val proxyClient = ProxyClient()
    proxyClient.launchServer(port, server)
//    proxyClient.test()
}