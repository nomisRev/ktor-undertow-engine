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
import java.io.OutputStream
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
            // Ensure we're on a worker thread and blocking mode is enabled
            if (exchange.isInIoThread) {
                throw IllegalStateException("Cannot access response channel from I/O thread")
            }
            
            if (!exchange.isBlocking) {
                exchange.startBlocking()
            }
            
            // Create a ByteWriteChannel that writes directly to Undertow's OutputStream
            _responseChannel = exchange.outputStream.toByteWriteChannel()
        }
        return _responseChannel!!
    }
    
    suspend fun finishResponse() {
        // Ensure response channel is flushed and closed if it was created
        _responseChannel?.let { channel ->
            try {
                if (!channel.isClosedForWrite) {
                    channel.flushAndClose()
                }
            } catch (e: Exception) {
                // Response channel might already be closed
            }
        }
        responseSent = true
    }
}

/**
 * Convert OutputStream to ByteWriteChannel using a simple approach
 */
private fun OutputStream.toByteWriteChannel(): ByteWriteChannel {
    val outputStream = this
    val channel = ByteChannel()
    
    // Use GlobalScope to avoid cancellation issues during response writing
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val buffer = ByteArray(8192)
            while (!channel.isClosedForRead) {
                val bytesRead = channel.readAvailable(buffer)
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                } else {
                    yield()
                }
            }
        } catch (e: Exception) {
            // Ignore exceptions during copy
        } finally {
            try {
                outputStream.flush()
            } catch (ignored: Exception) {
            }
        }
    }
    
    return channel
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

