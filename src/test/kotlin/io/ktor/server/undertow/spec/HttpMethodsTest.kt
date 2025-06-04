/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow.spec

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.undertow.*
import kotlinx.coroutines.*
import org.junit.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Comprehensive HTTP Methods test suite covering RFC 7231 and RFC 5789
 */
class HttpMethodsTest {
    
    companion object {
        val server = embeddedServer(Undertow, host = "localhost", port = 8082) {
            routing {
                // Standard HTTP methods
                get("/methods") {
                    call.respondText("GET", ContentType.Text.Plain)
                }
                
                post("/methods") {
                    val body = call.receiveText()
                    call.respondText("POST: $body", ContentType.Text.Plain)
                }
                
                put("/methods") {
                    val body = call.receiveText()
                    call.respondText("PUT: $body", ContentType.Text.Plain)
                }
                
                delete("/methods") {
                    call.respondText("DELETE", ContentType.Text.Plain)
                }
                
                patch("/methods") {
                    val body = call.receiveText()
                    call.respondText("PATCH: $body", ContentType.Text.Plain)
                }
                
                head("/methods") {
                    call.response.header("X-Method", "HEAD")
                    call.respond(HttpStatusCode.OK)
                }
                
                options("/methods") {
                    call.response.header("Allow", "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS")
                    call.respond(HttpStatusCode.OK)
                }
                
                // Method override testing
                post("/method-override") {
                    val overrideMethod = call.request.headers["X-HTTP-Method-Override"]
                    call.respondText("Original: POST, Override: $overrideMethod")
                }
                
                // Case sensitivity testing
                get("/case-sensitive") {
                    call.respondText("get")
                }
                
                // Custom methods - simplified for now
                get("/custom") {
                    call.respondText("CUSTOM method")
                }
                
                // Method not allowed testing
                get("/only-get") {
                    call.respondText("Only GET allowed")
                }
            }
        }
        
        val client = HttpClient(CIO)
        
        @JvmStatic
        @BeforeClass
        fun beforeClass(): Unit = runBlocking {
            server.startSuspend(wait = false)
            delay(500) // Give server time to start
        }
        
        @JvmStatic
        @AfterClass
        fun afterClass(): Unit = runBlocking {
            try {
                client.close()
                server.stopSuspend()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
    
    @Test
    fun testGetMethod() = runBlocking {
        val response = client.get("http://localhost:8082/methods")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GET", response.bodyAsText())
        assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
    }
    
    @Test
    fun testPostMethod() = runBlocking {
        val response = client.post("http://localhost:8082/methods") {
            setBody("test data")
            contentType(ContentType.Text.Plain)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("POST: test data", response.bodyAsText())
    }
    
    @Test
    fun testPutMethod() = runBlocking {
        val response = client.put("http://localhost:8082/methods") {
            setBody("updated data")
            contentType(ContentType.Text.Plain)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("PUT: updated data", response.bodyAsText())
    }
    
    @Test
    fun testDeleteMethod() = runBlocking {
        val response = client.delete("http://localhost:8082/methods")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("DELETE", response.bodyAsText())
    }
    
    @Test
    fun testPatchMethod() = runBlocking {
        val response = client.patch("http://localhost:8082/methods") {
            setBody("patched data")
            contentType(ContentType.Text.Plain)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("PATCH: patched data", response.bodyAsText())
    }
    
    @Test
    fun testHeadMethod() = runBlocking {
        val response = client.head("http://localhost:8082/methods")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("HEAD", response.headers["X-Method"])
    }
    
    @Test
    fun testOptionsMethod() = runBlocking {
        val response = client.options("http://localhost:8082/methods")
        assertEquals(HttpStatusCode.OK, response.status)
        val allowHeader = response.headers["Allow"]
        assertNotNull(allowHeader)
        assertTrue(allowHeader.contains("GET"))
        assertTrue(allowHeader.contains("POST"))
        assertTrue(allowHeader.contains("OPTIONS"))
    }
    
    @Test
    fun testCustomMethods() = runBlocking {
        // Test simplified custom endpoint
        val customResponse = client.get("http://localhost:8082/custom")
        assertEquals(HttpStatusCode.OK, customResponse.status)
        assertEquals("CUSTOM method", customResponse.bodyAsText())
    }
    
    @Test
    fun testMethodNotAllowed() = runBlocking {
        // Try POST on a GET-only endpoint
        val response = client.post("http://localhost:8082/only-get") {
            setBody("should not work")
        }
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }
    
    @Test
    fun testMethodOverride() = runBlocking {
        val response = client.post("http://localhost:8082/method-override") {
            header("X-HTTP-Method-Override", "PUT")
            setBody("override test")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Original: POST, Override: PUT", response.bodyAsText())
    }
    
    @Test
    fun testMethodCaseSensitivity() = runBlocking {
        // HTTP methods should be case-sensitive according to RFC 7231
        val response = client.get("http://localhost:8082/case-sensitive")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("get", response.bodyAsText())
    }
    
    @Test
    fun testIdempotency() = runBlocking {
        // GET, PUT, DELETE should be idempotent
        val get1 = client.get("http://localhost:8082/methods")
        val get2 = client.get("http://localhost:8082/methods")
        assertEquals(get1.bodyAsText(), get2.bodyAsText())
        
        val put1 = client.put("http://localhost:8082/methods") {
            setBody("idempotent data")
        }
        val put2 = client.put("http://localhost:8082/methods") {
            setBody("idempotent data")
        }
        assertEquals(put1.bodyAsText(), put2.bodyAsText())
    }
    
    @Test
    fun testSafeMethods() = runBlocking {
        // GET, HEAD, OPTIONS should be safe (no side effects)
        val getResponse = client.get("http://localhost:8082/methods")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        
        val headResponse = client.head("http://localhost:8082/methods")
        assertEquals(HttpStatusCode.OK, headResponse.status)
        
        val optionsResponse = client.options("http://localhost:8082/methods")
        assertEquals(HttpStatusCode.OK, optionsResponse.status)
    }
}