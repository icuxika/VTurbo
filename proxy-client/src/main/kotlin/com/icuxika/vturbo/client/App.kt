package com.icuxika.vturbo.client

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default

fun main(args: Array<String>) {
    val parser = ArgParser("vturbo-client")
    val port by parser.option(ArgType.Int, shortName = "p", description = "Server port").default(8881)
    parser.parse(args)

    val proxyClient = ProxyClient()
//    proxyClient.launchServer(port)
    proxyClient.test()
}