/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.events.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.util.pipeline.*
import io.undertow.server.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * Undertow [HttpHandler] that bridges Undertow requests to Ktor pipeline execution
 */
internal class UndertowApplicationCallHandler(
    private val application: Application,
    private val environment: ApplicationEnvironment,
    private val monitor: Events,
    private val developmentMode: Boolean
) : HttpHandler {

    private val coroutineScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("undertow-handler")
    )

    override fun handleRequest(exchange: HttpServerExchange) {
        if (exchange.isInIoThread) {
            exchange.dispatch(this)
            return
        }

        // Create Ktor application call from Undertow exchange
        val call = UndertowApplicationCall(application, exchange)
        
        // Execute the call synchronously to ensure proper completion
        runBlocking {
            try {
                application.execute(call)
            } catch (throwable: Throwable) {
                try {
                    call.response.status(HttpStatusCode.InternalServerError)
                } catch (ignored: Exception) {
                }
                if (developmentMode) {
                    throwable.printStackTrace()
                }
                environment.log.error("Request processing failed", throwable)
            } finally {
                call.finish()
            }
        }
    }
}