package com.icuxika.vturbo.client.server

import com.icuxika.vturbo.client.protocol.ProtocolHandle
import com.icuxika.vturbo.commons.extensions.logger
import com.icuxika.vturbo.commons.tcp.ByteArrayEvent
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.ExceptionHandler
import com.lmax.disruptor.SleepingWaitStrategy
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import com.lmax.disruptor.util.DaemonThreadFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

abstract class AbstractProxyServer(private val proxyServerAddress: String) : ProxyServer {
    /**
     * [ProtocolHandle.getId] <--> [ProtocolHandle]
     */
    val protocolHandleMap = ConcurrentHashMap<Int, ProtocolHandle>()

    private val forwardRequestToProxyServerDisruptor =
        Disruptor({ ByteArrayEvent() }, 1024, DaemonThreadFactory.INSTANCE, ProducerType.SINGLE, SleepingWaitStrategy())

    init {
        startForwardRequestToProxyServerDisruptor()
    }

    fun initProxyServer() {
        val (proxyServerHostname, proxyServerPort) = proxyServerAddress.split(":")
        LOGGER.info("代理服务器地址->$proxyServerHostname:$proxyServerPort")
        initProxyServerImpl(InetSocketAddress(proxyServerHostname, proxyServerPort.toInt()))
    }

    /**
     * 转发app的请求数据到代理服务端
     */
    override fun forwardRequestToProxyServer(data: ByteArray) {
        forwardRequestToProxyServerDisruptor.ringBuffer.publishEvent { event, _ -> event.value = data }
    }

    /**
     * 注册[ProtocolHandle]到[AbstractProxyServer]
     */
    override fun registerProtocolHandle(protocolHandle: ProtocolHandle) {
        protocolHandleMap[protocolHandle.getId()] = protocolHandle
    }

    /**
     * 从[AbstractProxyServer]中取消注册[ProtocolHandle]
     */
    override fun unregisterProtocolHandle(protocolHandle: ProtocolHandle) {
        protocolHandleMap.remove(protocolHandle.getId())
    }

    /**
     * 配置并启动Disruptor用于转发app的请求数据到代理服务端
     */
    private fun startForwardRequestToProxyServerDisruptor() {
        val eventHandler =
            EventHandler<ByteArrayEvent> { event, _, _ ->
                event?.let {
                    forwardRequestToProxyServerImpl(it.value)
                }
            }
        forwardRequestToProxyServerDisruptor.handleEventsWith(eventHandler)
        forwardRequestToProxyServerDisruptor.handleExceptionsFor(eventHandler)
            .with(object : ExceptionHandler<ByteArrayEvent> {
                override fun handleEventException(ex: Throwable?, sequence: Long, event: ByteArrayEvent?) {
                    ex?.let {
                        LOGGER.error("Disruptor处理事件时遇到错误[${it.message}]", it)
                        if (it is IOException) {
                            // 向代理服务端写入数据时如果发生了的异常，读取线程也会报错并关闭所有连接，然后shutdown Disruptor，此处异常应该很难看到触发
                            throw RuntimeException(ex)
                        }
                    }
                }

                override fun handleOnStartException(ex: Throwable?) {
                    ex?.let {
                        LOGGER.error("Disruptor启动时遇到错误[${it.message}]", it)
                    }
                }

                override fun handleOnShutdownException(ex: Throwable?) {
                    ex?.let {
                        LOGGER.error("Disruptor停止时遇到错误[${it.message}]", it)
                    }
                }
            })
        forwardRequestToProxyServerDisruptor.start()
    }

    /**
     * 关闭Disruptor
     */
    fun shutdownDisruptor() {
        forwardRequestToProxyServerDisruptor.shutdown()
    }

    /**
     * 连接代理服务端并不断读取数据
     */
    abstract fun initProxyServerImpl(inetSocketAddress: InetSocketAddress)

    /**
     * 转发app的请求数据到代理服务端
     */
    abstract fun forwardRequestToProxyServerImpl(data: ByteArray)

    companion object {
        val LOGGER = logger()
    }
}