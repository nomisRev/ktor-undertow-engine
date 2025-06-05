/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.undertow.server.HttpServerExchange
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.Executor

/**
 * Undertow-specific CoroutineDispatcher that ensures coroutines are executed on Undertow's worker thread pool.
 * This dispatcher provides proper structured concurrency and context preservation for Undertow-based applications.
 */
internal object UndertowDispatcher : CoroutineDispatcher() {
    
    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        val currentContext = context[CurrentContextKey]
        return currentContext?.exchange?.isInIoThread != false
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val undertowContext = context[CurrentContextKey]
        if (undertowContext != null) {
            val exchange = undertowContext.exchange
            if (exchange.isInIoThread) {
                // Dispatch to worker thread
                exchange.dispatch(block)
            } else {
                // Already on worker thread, execute directly
                block.run()
            }
        } else {
            // Fallback to default dispatcher if no Undertow context
            Dispatchers.Default.dispatch(context, block)
        }
    }

    /**
     * Coroutine context element that holds the current HttpServerExchange.
     * This allows the dispatcher to access Undertow's threading model.
     */
    class CurrentContext(val exchange: HttpServerExchange) : AbstractCoroutineContextElement(CurrentContextKey)
    
    /**
     * Key for accessing the current Undertow context in the coroutine context.
     */
    object CurrentContextKey : CoroutineContext.Key<CurrentContext>
}