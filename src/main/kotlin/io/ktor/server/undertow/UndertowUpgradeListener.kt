/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.utils.io.*
import io.ktor.utils.io.ByteChannel
import io.undertow.server.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.xnio.*
import org.xnio.channels.*
import java.io.*
import java.nio.*
import kotlin.coroutines.*

/**
 * Undertow's HttpUpgradeListener implementation for handling protocol upgrades
 */
internal class UndertowUpgradeListener(
    private val upgrade: OutgoingContent.ProtocolUpgrade,
    private val engineContext: CoroutineContext,
    private val logger: Logger
) : HttpUpgradeListener {
    
    override fun handleUpgrade(connection: StreamConnection, exchange: HttpServerExchange) {
        try {
            // Create input/output channels from the connection
            val inputChannel = connection.sourceChannel.toByteReadChannel(engineContext)
            val outputChannel = connection.sinkChannel.toByteWriteChannel(engineContext)
            
            // Launch the upgrade handler in a coroutine with proper scope
            // Use GlobalScope to prevent cancellation when connection closes
            GlobalScope.launch(engineContext) {
                try {
                    val job = upgrade.upgrade(
                        input = inputChannel,
                        output = outputChannel,
                        engineContext = engineContext,
                        userContext = engineContext
                    )
                    job.join()
                } catch (e: Exception) {
                    logger.error("Error in protocol upgrade handler", e)
                } finally {
                    try {
                        // Close channels first
                        inputChannel.cancel()
                        outputChannel.close()
                        // Then close connection
                        connection.close()
                    } catch (e: Exception) {
                        logger.debug("Error closing upgraded connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error setting up protocol upgrade", e)
            try {
                connection.close()
            } catch (closeException: Exception) {
                logger.debug("Error closing connection after upgrade failure", closeException)
            }
        }
    }
}

/**
 * Convert XNIO StreamSourceChannel to ByteReadChannel
 */
private fun StreamSourceChannel.toByteReadChannel(context: CoroutineContext): ByteReadChannel {
    val sourceChannel = this
    val channel = ByteChannel(autoFlush = true)
    
    // Use provided context instead of GlobalScope
    CoroutineScope(context + Dispatchers.IO).launch {
        try {
            val buffer = ByteBuffer.allocate(8192)
            while (sourceChannel.isOpen && !channel.isClosedForWrite) {
                buffer.clear()
                val bytesRead = try {
                    sourceChannel.read(buffer)
                } catch (e: Exception) {
                    // Channel closed or error
                    break
                }
                
                when {
                    bytesRead == -1 -> break // End of stream
                    bytesRead > 0 -> {
                        buffer.flip()
                        val bytes = ByteArray(bytesRead)
                        buffer.get(bytes)
                        try {
                            channel.writeFully(bytes)
                            channel.flush()
                        } catch (e: Exception) {
                            // Channel closed
                            break
                        }
                    }
                    else -> {
                        // No data available, yield to avoid busy waiting
                        delay(1)
                    }
                }
            }
        } catch (e: Exception) {
            // Connection closed or error occurred
        } finally {
            try {
                channel.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }
    
    return channel
}

/**
 * Convert XNIO StreamSinkChannel to ByteWriteChannel
 */
private fun StreamSinkChannel.toByteWriteChannel(context: CoroutineContext): ByteWriteChannel {
    val sinkChannel = this
    val channel = ByteChannel(autoFlush = true)
    
    // Use provided context instead of GlobalScope
    CoroutineScope(context + Dispatchers.IO).launch {
        try {
            val buffer = ByteArray(8192)
            while (!channel.isClosedForRead && sinkChannel.isOpen) {
                val bytesRead = try {
                    channel.readAvailable(buffer)
                } catch (e: Exception) {
                    // Channel closed
                    break
                }
                
                when {
                    bytesRead == -1 -> break // End of stream
                    bytesRead > 0 -> {
                        val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
                        try {
                            while (byteBuffer.hasRemaining() && sinkChannel.isOpen) {
                                sinkChannel.write(byteBuffer)
                            }
                            sinkChannel.flush()
                        } catch (e: Exception) {
                            // Sink channel closed or error
                            break
                        }
                    }
                    else -> {
                        // No data available, yield to avoid busy waiting
                        delay(1)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore exceptions during copy
        } finally {
            try {
                sinkChannel.flush()
            } catch (ignored: Exception) {
                // Ignore flush errors
            }
        }
    }
    
    return channel
}