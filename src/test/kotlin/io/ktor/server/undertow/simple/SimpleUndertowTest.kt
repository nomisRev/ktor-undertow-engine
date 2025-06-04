/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow.simple

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.undertow.*
import kotlinx.coroutines.*
import kotlin.test.*

class SimpleUndertowTest {
    
    @Test
    fun testSimpleResponse() = runBlocking {
        val server = embeddedServer(Undertow, host = "localhost", port = 8081) {
            routing {
                get("/test") {
                    call.respondText("OK")
                }
            }
        }
        
        server.start(wait = false)
        delay(500) // Give server time to start
        
        try {
            val client = HttpClient(CIO)
            val response = client.get("http://localhost:8081/test")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
            client.close()
        } finally {
            server.stop()
        }
    }
}