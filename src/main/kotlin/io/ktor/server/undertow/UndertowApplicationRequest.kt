/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import io.ktor.util.logging.Logger
import io.undertow.server.*
import io.undertow.util.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * Undertow implementation of [ApplicationRequest]
 */
internal class UndertowApplicationRequest(
    call: PipelineCall,
    internal val exchange: HttpServerExchange
) : BaseApplicationRequest(call), CoroutineScope {

    override val coroutineContext: CoroutineContext = call.coroutineContext

    override val engineHeaders: io.ktor.http.Headers = UndertowRequestHeaders(exchange)

    override val engineReceiveChannel: ByteReadChannel by lazy {
        if (!exchange.isBlocking) {
            exchange.startBlocking()
        }
        
        if (exchange.isRequestChannelAvailable) {
            exchange.inputStream.toByteReadChannel(this@UndertowApplicationRequest, call.application.environment.log)
        } else {
            ByteReadChannel.Empty
        }
    }

    override val queryParameters: Parameters by lazy {
        val queryString = exchange.queryString ?: ""
        parseQueryString(queryString)
    }

    override val rawQueryParameters: Parameters by lazy {
        val queryString = exchange.queryString ?: ""
        parseQueryString(queryString, decode = false)
    }

    override val cookies: RequestCookies = UndertowRequestCookies(this)

    override val local: RequestConnectionPoint = UndertowRequestConnectionPoint(exchange)
}

/**
 * Undertow implementation of request headers
 */
private class UndertowRequestHeaders(private val exchange: HttpServerExchange) : io.ktor.http.Headers {
    override val caseInsensitiveName: Boolean = true

    override fun getAll(name: String): List<String>? {
        return exchange.requestHeaders.get(name)?.toList()
    }

    override fun names(): Set<String> {
        return exchange.requestHeaders.headerNames.map { it.toString() }.toSet()
    }

    override fun entries(): Set<Map.Entry<String, List<String>>> {
        return exchange.requestHeaders.headerNames.map { headerName ->
            val values = exchange.requestHeaders.get(headerName).toList()
            object : Map.Entry<String, List<String>> {
                override val key: String = headerName.toString()
                override val value: List<String> = values
            }
        }.toSet()
    }

    override fun isEmpty(): Boolean = exchange.requestHeaders.size() == 0
}

/**
 * Undertow implementation of request cookies
 */
private class UndertowRequestCookies(private val undertowRequest: UndertowApplicationRequest) : RequestCookies(undertowRequest) {
    override fun fetchCookies(): Map<String, String> {
        val cookies = undertowRequest.exchange.requestCookies()
        return if (cookies != null) {
            val result = mutableMapOf<String, String>()
            for (cookie in cookies) {
                result[cookie.name] = cookie.value
            }
            result
        } else {
            emptyMap()
        }
    }
}

/**
 * Undertow implementation of request connection point
 */
private class UndertowRequestConnectionPoint(private val exchange: HttpServerExchange) : RequestConnectionPoint {
    override val scheme: String = if (exchange.requestScheme == "https") "https" else "http"
    override val version: String = exchange.protocol.toString()
    override val uri: String = exchange.requestURI + if (exchange.queryString.isNullOrEmpty()) "" else "?${exchange.queryString}"
    override val method: HttpMethod = HttpMethod.parse(exchange.requestMethod.toString())
    override val port: Int = exchange.hostPort
    override val host: String = exchange.hostName ?: "localhost"
    override val remoteHost: String = exchange.sourceAddress?.address?.hostAddress ?: "unknown"
    override val remotePort: Int = exchange.sourceAddress?.port ?: 0
    override val remoteAddress: String = remoteHost
    override val localHost: String = exchange.destinationAddress?.address?.hostAddress ?: "localhost"
    override val localPort: Int = exchange.destinationAddress?.port ?: port
    override val localAddress: String = localHost
    override val serverHost: String = host
    override val serverPort: Int = port
}

/**
 * Extension to convert Undertow's InputStream to ByteReadChannel using structured concurrency
 */
private fun java.io.InputStream.toByteReadChannel(scope: CoroutineScope, logger: Logger): ByteReadChannel {
    val inputStream = this
    return scope.writer(Dispatchers.IO) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                if (bytesRead == 0) {
                    // No data available, yield to avoid busy waiting
                    yield()
                    continue
                }
                channel.writeFully(buffer, 0, bytesRead)
            }
        } catch (e: Throwable) {
            channel.close(e)
        } finally {
            try {
                inputStream.close()
            } catch (ignored: Exception) {
                logger.error("Failed to close input stream", ignored)
            }
        }
    }.channel
}