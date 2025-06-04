/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import io.undertow.server.*
import io.undertow.util.*
import kotlinx.coroutines.*
import java.nio.*
import kotlin.coroutines.*

/**
 * Undertow implementation of [ApplicationResponse]
 */
internal class UndertowApplicationResponse(
    call: PipelineCall,
    private val exchange: HttpServerExchange
) : BaseApplicationResponse(call), CoroutineScope {

    override val coroutineContext: CoroutineContext = call.coroutineContext

    override val headers: ResponseHeaders = UndertowResponseHeaders(exchange)

    private var _status: HttpStatusCode? = null
    private var responseSent = false

    override fun setStatus(statusCode: HttpStatusCode) {
        _status = statusCode
        if (!exchange.isResponseStarted) {
            exchange.statusCode = statusCode.value
        }
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        throw UnsupportedOperationException("Protocol upgrade is not supported in Undertow engine")
    }

    private var _responseChannel: ByteWriteChannel? = null
    
    override suspend fun responseChannel(): ByteWriteChannel {
        if (_responseChannel == null) {
            if (!exchange.isBlocking) {
                exchange.startBlocking()
            }
            _responseChannel = exchange.outputStream.toByteWriteChannel(this@UndertowApplicationResponse)
        }
        return _responseChannel!!
    }
    
    suspend fun finishResponse() {
        // Ensure response channel is flushed and closed if it was created
        _responseChannel?.let { channel ->
            try {
                channel.flushAndClose()
            } catch (e: Exception) {
                // Response channel might already be closed
            }
        }
    }
}

/**
 * Undertow implementation of response headers
 */
private class UndertowResponseHeaders(private val exchange: HttpServerExchange) : ResponseHeaders() {
    
    override fun engineAppendHeader(name: String, value: String) {
        if (!exchange.isResponseStarted) {
            exchange.responseHeaders.add(HttpString.tryFromString(name), value)
        }
    }

    override fun getEngineHeaderNames(): List<String> {
        return exchange.responseHeaders.headerNames.map { it.toString() }
    }

    override fun getEngineHeaderValues(name: String): List<String> {
        return exchange.responseHeaders.get(name)?.toList() ?: emptyList()
    }
}

/**
 * Extension to convert Undertow's OutputStream to ByteWriteChannel using structured concurrency
 */
private fun java.io.OutputStream.toByteWriteChannel(scope: CoroutineScope): ByteWriteChannel {
    val outputStream = this
    val channel = ByteChannel()
    
    // Use structured concurrency with proper parent scope
    val job = scope.launch(Dispatchers.IO) {
        try {
            val buffer = ByteArray(8192)
            while (!channel.isClosedForRead) {
                val bytesRead = channel.readAvailable(buffer)
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                }
                if (bytesRead == 0) {
                    yield() // Avoid busy waiting
                }
            }
        } catch (e: Exception) {
            // Handle exceptions silently
        } finally {
            try {
                outputStream.flush()
                outputStream.close()
            } catch (ignored: Exception) {
            }
        }
    }
    
    // Return a wrapper that properly handles completion
    return object : ByteWriteChannel by channel {
        override suspend fun flushAndClose() {
            channel.flushAndClose()
            // Wait for completion without blocking
            job.join()
        }
    }
}