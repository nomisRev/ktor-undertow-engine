/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.utils.io.*
import io.undertow.server.*
import kotlinx.coroutines.*
import java.io.*

/**
 * Represents an upgraded connection in Undertow that provides raw I/O access
 * for protocols like WebSocket after a successful protocol upgrade.
 */
internal class UndertowUpgradedConnection(private val exchange: HttpServerExchange) {
    private val inputStream: InputStream = exchange.inputStream
    private val outputStream: OutputStream = exchange.outputStream

    /**
     * Input channel for reading from the upgraded connection
     */
    val input: ByteReadChannel by lazy {
        inputStream.toByteReadChannel()
    }

    /**
     * Output channel for writing to the upgraded connection
     */
    val output: ByteWriteChannel by lazy {
        outputStream.toByteWriteChannel()
    }

    /**
     * Closes the upgraded connection
     */
    fun close() {
        var exception: Exception? = null
        try {
            inputStream.close()
        } catch (e: Exception) {
            exception = e
        }
        try {
            outputStream.close()
        } catch (e: Exception) {
            if (exception != null) {
                exception.addSuppressed(e)
            } else {
                exception = e
            }
        }
        exception?.let { throw it }
    }
}

/**
 * Convert InputStream to ByteReadChannel
 */
private fun InputStream.toByteReadChannel(): ByteReadChannel {
    val inputStream = this
    val channel = ByteChannel(autoFlush = true)

    GlobalScope.launch(Dispatchers.IO) {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    channel.writeFully(buffer, 0, bytesRead)
                    channel.flush()
                }
            }
        } catch (e: Exception) {
            // Connection closed or error occurred
        } finally {
            channel.close()
        }
    }

    return channel
}

/**
 * Convert OutputStream to ByteWriteChannel (reusing the existing implementation)
 */
private fun OutputStream.toByteWriteChannel(): ByteWriteChannel {
    val outputStream = this
    val channel = ByteChannel(autoFlush = true)

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