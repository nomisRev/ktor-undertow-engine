/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.undertow.Undertow
import io.undertow.server.*
import kotlinx.coroutines.*
import javax.net.ssl.*

/**
 * Undertow-based [ApplicationEngine] implementation
 */
public class UndertowApplicationEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    private val developmentMode: Boolean,
    public val configuration: Configuration,
    private val applicationProvider: () -> Application
) : BaseApplicationEngine(environment, monitor, developmentMode) {

    /**
     * Configuration for the [UndertowApplicationEngine]
     */
    public class Configuration : BaseApplicationEngine.Configuration() {
        /**
         * Number of worker threads
         */
        public var workerThreads: Int = Runtime.getRuntime().availableProcessors() * 8

        /**
         * Number of I/O threads
         */
        public var ioThreads: Int = Runtime.getRuntime().availableProcessors()

        /**
         * Buffer size for I/O operations
         */
        public var bufferSize: Int = 16384

        /**
         * Whether to use direct buffers
         */
        public var directBuffers: Boolean = true
    }

    private var server: Undertow? = null

    override fun start(wait: Boolean): UndertowApplicationEngine {
        val undertowBuilder = Undertow.builder()
            .setWorkerThreads(configuration.workerThreads)
            .setIoThreads(configuration.ioThreads)
            .setBufferSize(configuration.bufferSize)
            .setDirectBuffers(configuration.directBuffers)

        configuration.connectors.forEach { connector ->
            when (connector.type) {
                ConnectorType.HTTP -> {
                    undertowBuilder.addHttpListener(connector.port, connector.host)
                }
                ConnectorType.HTTPS -> {
                    val sslContext = createSSLContext(connector)
                    undertowBuilder.addHttpsListener(connector.port, connector.host, sslContext)
                }
            }
        }

        val userContext = applicationProvider().coroutineContext +
                          DefaultUncaughtExceptionHandler(environment.log)

        val handler = UndertowApplicationCallHandler(applicationProvider(), environment, userContext)
        server = undertowBuilder.setHandler(handler).build()
        
        server?.start()
        
        // Complete the resolved connectors
        val resolvedConnectors = configuration.connectors.map { connector ->
            connector.withPort(connector.port) // In a real implementation, get actual bound port
        }
        resolvedConnectorsDeferred.complete(resolvedConnectors)
        
        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        server?.stop()
        server = null
    }

    private fun createSSLContext(connector: EngineConnectorConfig): SSLContext {
        // For now, create a simple SSL context
        // In a real implementation, you would configure this properly
        return SSLContext.getDefault()
    }
}