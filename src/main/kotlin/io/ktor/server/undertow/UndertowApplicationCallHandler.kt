/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.events.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.util.pipeline.*
import io.undertow.server.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * Undertow [HttpHandler] that bridges Undertow requests to Ktor pipeline execution
 */
internal class UndertowApplicationCallHandler(
    private val application: Application,
    private val environment: ApplicationEnvironment,
    override val coroutineContext: CoroutineContext
) : HttpHandler, CoroutineScope {
    override fun handleRequest(exchange: HttpServerExchange) {
        // Dispatch to worker thread to avoid blocking I/O thread
        if (exchange.isInIoThread) {
            exchange.dispatch(this)
            return
        }
        
        // Create Ktor application call from Undertow exchange
        val call = UndertowApplicationCall(application, exchange)
        
        // Use runBlocking to ensure the exchange doesn't complete until processing is done
        runBlocking(coroutineContext) {
            try {
                application.execute(call)
            } catch (error: Throwable) {
                environment.log.error("Application ${application::class.java} cannot fulfill the request", error)
                try {
                    call.respond(HttpStatusCode.InternalServerError)
                } catch (responseError: Throwable) {
                    environment.log.error("Failed to send error response", responseError)
                }
            } finally {
                // Ensure response is properly finished
                try {
                    (call.response as UndertowApplicationResponse).finishResponse()
                } catch (finishError: Throwable) {
                    environment.log.debug("Error finishing response", finishError)
                }
            }
        }
    }
}