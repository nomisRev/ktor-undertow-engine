/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.*
import java.net.Socket

class UndertowEngineTest {

    @Test
    fun testBasicGetRequest() = runBlocking {
        val response = client.get("http://localhost:8080/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, World!", response.bodyAsText())
    }

    @Test
    fun testGetWithParameters() = runBlocking {
        val response = client.get("http://localhost:8080/echo?message=test&number=42")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("message=test, number=42", response.bodyAsText())
    }

    @Test
    fun testPostWithBody() = runBlocking {
        val response = client.post("http://localhost:8080/echo-body") {
            setBody("Hello from client")
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Received: Hello from client", response.bodyAsText())
    }

    @Test
    fun testPutRequest() = runBlocking {
        val response = client.put("http://localhost:8080/update") {
            setBody("Updated data")
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("PUT: Updated data", response.bodyAsText())
    }

    @Test
    fun testDeleteRequest() = runBlocking {
        val response = client.delete("http://localhost:8080/delete/123")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Deleted item: 123", response.bodyAsText())
    }

    @Test
    fun testCustomHeaders() = runBlocking {
        val response = client.get("http://localhost:8080/headers") {
            header("X-Custom-Header", "test-value")
            header("X-Another-Header", "another-value")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "test-value",
            response.headers["X-Headers"] ?: fail("X-Headers header is missing")
        )
        assertEquals(
            expected = "another-value",
            actual = response.headers["X-Another-Header"] ?: fail("X-Another-Header header is missing")
        )
    }

    @Test
    fun testResponseHeaders() = runBlocking {
        val response = client.get("http://localhost:8080/response-headers")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("custom-value", response.headers["X-Custom-Response"])
        assertEquals("application/json", response.headers[HttpHeaders.ContentType])
    }

    @Test
    fun testStatusCodes() = runBlocking {
        // Test 201 Created
        val created = client.post("http://localhost:8080/create")
        assertEquals(HttpStatusCode.Created, created.status)

        // Test 404 Not Found
        val notFound = client.get("http://localhost:8080/nonexistent")
        assertEquals(HttpStatusCode.NotFound, notFound.status)

        // Test 500 Internal Server Error
        val error = client.get("http://localhost:8080/error")
        assertEquals(HttpStatusCode.InternalServerError, error.status)
    }

    @Test
    fun testJsonResponse() = runBlocking {
        val response = client.get("http://localhost:8080/json")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val body = response.bodyAsText()
        assertTrue(body.contains("\"message\""))
        assertTrue(body.contains("\"status\""))
    }

    @Test
    fun testCookies() = runBlocking {
        val response = client.get("http://localhost:8080/set-cookie")
        assertEquals(HttpStatusCode.OK, response.status)
        val setCookieHeader = response.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookieHeader)
        assertTrue(setCookieHeader.contains("test-cookie=test-value"))
    }

    @Test
    fun testLargeResponse() = runBlocking {
        val response = client.get("http://localhost:8080/large")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(10000, body.length) // Should be 10000 'A' characters
        assertTrue(body.all { it == 'A' })
    }

    @Test
    fun testConcurrentRequests() = runBlocking {
        val responses = (0..10).map { i ->
            async {
                client.get("http://localhost:8080/concurrent/$i")
            }
        }.awaitAll()
        responses.forEachIndexed { index, response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Request $index processed", response.bodyAsText())
        }
    }

    @Test
    fun testServerIsListening() {
        // Verify the server is actually listening on the port
        Socket("localhost", 8080).use { socket ->
            assertTrue(socket.isConnected)
        }
    }

    companion object {
        val server = embeddedServer(Undertow, host = "localhost", port = 8080) {
            routing {
                get("/") {
                    call.respondText("Hello, World!")
                }

                get("/echo") {
                    val message = call.request.queryParameters["message"] ?: "no-message"
                    val number = call.request.queryParameters["number"] ?: "no-number"
                    call.respondText("message=$message, number=$number")
                }

                post("/echo-body") {
                    val body = call.receiveText()
                    call.respondText("Received: $body")
                }

                put("/update") {
                    val body = call.receiveText()
                    call.respondText("PUT: $body")
                }

                delete("/delete/{id}") {
                    val id = call.parameters["id"]
                    call.respondText("Deleted item: $id")
                }

                get("/headers") {
                    // Set response headers based on request headers
                    val customHeader = call.request.headers["X-Custom-Header"]
                    if (customHeader != null) {
                        call.response.header("X-Headers", customHeader)
                    }

                    val anotherHeader = call.request.headers["X-Another-Header"]
                    if (anotherHeader != null) {
                        call.response.header("X-Another-Header", anotherHeader)
                    }

                    val headers = call.request.headers.entries()
                        .filter { it.key.startsWith("X-") }
                        .joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }
                    call.respondText(headers)
                }

                get("/response-headers") {
                    call.response.header("X-Custom-Response", "custom-value")
                    call.respondText("{\"message\": \"success\"}", ContentType.Application.Json)
                }

                post("/create") {
                    call.respond(HttpStatusCode.Created, "Resource created")
                }

                get("/nonexistent") {
                    call.respond(HttpStatusCode.NotFound, "Not found")
                }

                get("/error") {
                    call.respond(HttpStatusCode.InternalServerError, "Internal error")
                }

                get("/json") {
                    call.respondText(
                        "{\"message\": \"Hello JSON\", \"status\": \"success\"}",
                        ContentType.Application.Json
                    )
                }

                get("/set-cookie") {
                    call.response.cookies.append("test-cookie", "test-value")
                    call.respondText("Cookie set")
                }

                get("/large") {
                    call.respondText("A".repeat(10000))
                }

                get("/concurrent/{id}") {
                    val id = call.parameters["id"]
                    call.respondText("Request $id processed")
                }
            }
        }

        val client = HttpClient(CIO)

        @JvmStatic
        @BeforeClass
        fun beforeClass(): Unit = runBlocking {
            server.startSuspend(wait = false)
            // Give the server a moment to fully start
            delay(1000)
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
}