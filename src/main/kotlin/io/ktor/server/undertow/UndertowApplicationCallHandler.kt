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
 * using structured concurrency and proper context preservation.
 */
internal class UndertowApplicationCallHandler(
    private val application: Application,
    private val environment: ApplicationEnvironment,
    userCoroutineContext: CoroutineContext
) : HttpHandler, CoroutineScope {
    
    override val coroutineContext: CoroutineContext = userCoroutineContext + SupervisorJob()
    
    override fun handleRequest(exchange: HttpServerExchange) {
        // Prevent Undertow from automatically ending the exchange
        if (exchange.isInIoThread) {
            exchange.dispatch(this::handleRequest)
            return
        }
        
        val call = UndertowApplicationCall(application, exchange)
        
        // Create context with Undertow dispatcher and current exchange
        val callContext = coroutineContext + 
                         UndertowDispatcher + 
                         UndertowDispatcher.CurrentContext(exchange) +
                         CoroutineName("undertow-call")
        
        // Launch with structured concurrency using the Undertow dispatcher
        launch(callContext, start = CoroutineStart.UNDISPATCHED) {
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
                    call.response.finishResponse()
                } catch (finishError: Throwable) {
                    environment.log.debug("Error finishing response", finishError)
                }
                
                // End the exchange after processing is complete
                try {
                    if (!exchange.isResponseComplete) {
                        exchange.endExchange()
                    }
                } catch (endError: Throwable) {
                    environment.log.debug("Error ending exchange", endError)
                }
            }
        }
    }
}