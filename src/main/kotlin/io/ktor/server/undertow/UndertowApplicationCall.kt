/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.undertow.server.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

import io.ktor.utils.io.*

/**
 * Undertow implementation of [ApplicationCall]
 */
internal class UndertowApplicationCall(
    application: Application,
    private val exchange: HttpServerExchange
) : BaseApplicationCall(application) {

    override val coroutineContext: CoroutineContext = 
        SupervisorJob() + Dispatchers.Default + CoroutineName("undertow-call")

    override val request: UndertowApplicationRequest = UndertowApplicationRequest(this, exchange)
    override val response: UndertowApplicationResponse = UndertowApplicationResponse(this, exchange)

    init {
        putResponseAttribute()
    }
}