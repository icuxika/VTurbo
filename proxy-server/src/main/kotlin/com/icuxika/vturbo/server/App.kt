package com.icuxika.vturbo.server

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default


fun main(args: Array<String>) {
    val parser = ArgParser("vturbo-server")
    val port by parser.option(ArgType.Int, shortName = "p", description = "Server port").default(8882)
    parser.parse(args)

    System.setProperty("logback.configurationFile", "config/logback.xml")

    val proxyServer = ProxyServer()
    proxyServer.launchServer(port)
}