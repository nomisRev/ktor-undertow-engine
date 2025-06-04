/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow.spec

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.undertow.*
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.jupiter.api.assertNotNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Comprehensive HTTP Headers test suite covering RFC 7230-7235
 * Tests header handling, case-insensitivity, multiple values, and standard headers
 */
class HttpHeadersTest {

    companion object {
        private lateinit var server: EmbeddedServer<UndertowApplicationEngine, UndertowApplicationEngine.Configuration>
        private lateinit var client: HttpClient

        @BeforeClass
        @JvmStatic
        fun setup() {
            server = embeddedServer(Undertow, port = 8081) {
                routing {
                    get("/headers") {
                        val headers = call.request.headers
                        val responseHeaders = mutableListOf<String>()

                        headers.forEach { name, values ->
                            responseHeaders.add("$name: ${values.joinToString(", ")}")
                        }

                        call.respondText(responseHeaders.joinToString("\n"))
                    }

                    get("/case-insensitive") {
                        val contentType = call.request.headers["content-type"]
                        val userAgent = call.request.headers["User-Agent"]
                        val accept = call.request.headers["ACCEPT"]

                        call.respondText("Content-Type: $contentType, User-Agent: $userAgent, Accept: $accept")
                    }

                    get("/multiple-values") {
                        val acceptValues = call.request.headers.getAll("Accept")
                        val customValues = call.request.headers.getAll("X-Custom")

                        call.respondText("Accept: ${acceptValues?.joinToString(", ")}, X-Custom: ${customValues?.joinToString(", ")}")
                    }

                    post("/echo-headers") {
                        val requestHeaders = call.request.headers
                        call.response.headers.append("X-Echo-Count", requestHeaders.names().size.toString())

                        requestHeaders.forEach { name, values ->
                            if (name.startsWith("X-Echo-")) {
                                call.response.headers.append("Echo-$name", values.joinToString(", "))
                            }
                        }

                        call.respondText("Headers echoed")
                    }

                    get("/standard-headers") {
                        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
                        call.response.headers.append("Pragma", "no-cache")
                        call.response.headers.append("Expires", "0")
                        call.response.headers.append("X-Frame-Options", "DENY")
                        call.response.headers.append("X-Content-Type-Options", "nosniff")
                        call.response.headers.append("X-XSS-Protection", "1; mode=block")

                        call.respondText("Standard security headers set")
                    }

                    get("/cors") {
                        call.response.headers.append("Access-Control-Allow-Origin", "*")
                        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                        call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")
                        call.response.headers.append("Access-Control-Max-Age", "86400")

                        call.respondText("CORS headers set")
                    }

                    options("/cors") {
                        call.response.headers.append("Access-Control-Allow-Origin", "*")
                        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                        call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")
                        call.response.headers.append("Access-Control-Max-Age", "86400")

                        call.respond(HttpStatusCode.NoContent)
                    }

                    get("/conditional") {
                        val ifModifiedSince = call.request.headers["If-Modified-Since"]
                        val ifNoneMatch = call.request.headers["If-None-Match"]

                        if (ifNoneMatch == "\"test-etag\"") {
                            call.respond(HttpStatusCode.NotModified)
                            return@get
                        }

                        call.response.headers.append("ETag", "\"test-etag\"")
                        call.response.headers.append("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                        call.respondText("Resource content")
                    }

                    get("/content-negotiation") {
                        val accept = call.request.headers["Accept"]
                        val acceptLanguage = call.request.headers["Accept-Language"]
                        val acceptEncoding = call.request.headers["Accept-Encoding"]

                        when {
                            accept?.contains("application/json") == true -> {
                                call.response.headers.append("Content-Type", "application/json")
                                call.respondText("{\"message\": \"JSON response\"}")
                            }
                            accept?.contains("application/xml") == true -> {
                                call.response.headers.append("Content-Type", "application/xml")
                                call.respondText("<message>XML response</message>")
                            }
                            else -> {
                                call.response.headers.append("Content-Type", "text/plain")
                                call.respondText("Plain text response")
                            }
                        }
                    }

                    get("/custom-headers") {
                        call.response.headers.append("X-Custom-Header", "custom-value")
                        call.response.headers.append("X-Request-ID", "12345")
                        call.response.headers.append("X-Rate-Limit", "1000")
                        call.response.headers.append("X-Rate-Limit-Remaining", "999")

                        call.respondText("Custom headers set")
                    }

                    get("/header-validation") {
                        // Test various header formats and edge cases
                        val authorization = call.request.headers["Authorization"]
                        val cookie = call.request.headers["Cookie"]
                        val userAgent = call.request.headers["User-Agent"]

                        call.respondText("Auth: $authorization, Cookie: $cookie, UA: $userAgent")
                    }
                }
            }
            server.start(wait = false)

            client = HttpClient(CIO) {
                expectSuccess = false
            }
        }

        @AfterClass
        @JvmStatic
        fun teardown() {
            client.close()
            server.stop(1000, 2000)
        }
    }

    @Test
    fun testBasicHeaders() = runBlocking {
        val response = client.get("http://localhost:8081/headers") {
            header("User-Agent", "Test-Client/1.0")
            header("Accept", "text/plain")
            header("X-Custom", "test-value")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("User-Agent: Test-Client/1.0"))
        assertTrue(body.contains("Accept: text/plain"))
        assertTrue(body.contains("X-Custom: test-value"))
    }

    @Test
    fun testCaseInsensitiveHeaders() = runBlocking {
        val response = client.get("http://localhost:8081/case-insensitive") {
            header("content-type", "application/json")
            header("User-Agent", "Test-Client/1.0")
            header("ACCEPT", "application/json")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Content-Type: application/json"))
        assertTrue(body.contains("User-Agent: Test-Client/1.0"))
        assertTrue(body.contains("Accept: application/json"))
    }

    @Test
    fun testMultipleHeaderValues() = runBlocking {
        val response = client.get("http://localhost:8081/multiple-values") {
            header("Accept", "text/html")
            header("Accept", "application/json")
            header("X-Custom", "value1")
            header("X-Custom", "value2")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "Accept: text/html,application/json, X-Custom: value1,value2",
            response.bodyAsText()

        )
    }

    @Test
    fun testHeaderEcho() = runBlocking {
        val response = client.post("http://localhost:8081/echo-headers") {
            header("X-Echo-Test", "test-value")
            header("X-Echo-Another", "another-value")
            header("Regular-Header", "should-not-echo")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Headers echoed", response.bodyAsText())
        assertTrue(response.headers.contains("Echo-X-Echo-Test"))
        assertTrue(response.headers.contains("Echo-X-Echo-Another"))
        assertFalse(response.headers.contains("Echo-Regular-Header"))
    }

    @Test
    fun testStandardSecurityHeaders() = runBlocking {
        val response = client.get("http://localhost:8081/standard-headers")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-cache, no-store, must-revalidate", response.headers["Cache-Control"])
        assertEquals("no-cache", response.headers["Pragma"])
        assertEquals("0", response.headers["Expires"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("1; mode=block", response.headers["X-XSS-Protection"])
    }

    @Test
    fun testCorsHeaders() = runBlocking {
        val response = client.get("http://localhost:8081/cors")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("*", response.headers["Access-Control-Allow-Origin"])
        assertEquals("GET, POST, PUT, DELETE, OPTIONS", response.headers["Access-Control-Allow-Methods"])
        assertEquals("Content-Type, Authorization, X-Requested-With", response.headers["Access-Control-Allow-Headers"])
        assertEquals("86400", response.headers["Access-Control-Max-Age"])
    }

    @Test
    fun testCorsPreflightRequest() = runBlocking {
        val response = client.options("http://localhost:8081/cors") {
            header("Origin", "https://example.com")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "Content-Type")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("*", response.headers["Access-Control-Allow-Origin"])
        assertEquals("GET, POST, PUT, DELETE, OPTIONS", response.headers["Access-Control-Allow-Methods"])
    }

    @Test
    fun testConditionalHeaders() = runBlocking {
        // First request to get ETag
        val firstResponse = client.get("http://localhost:8081/conditional")
        assertEquals(HttpStatusCode.OK, firstResponse.status)
        val etag = firstResponse.headers["ETag"]
        assertNotNull(etag)

        // Second request with If-None-Match
        val secondResponse = client.get("http://localhost:8081/conditional") {
            header("If-None-Match", etag)
        }
        assertEquals(HttpStatusCode.NotModified, secondResponse.status)
    }

    @Test
    fun testContentNegotiation() = runBlocking {
        // Test JSON content negotiation
        val jsonResponse = client.get("http://localhost:8081/content-negotiation") {
            header("Accept", "application/json")
        }
        assertEquals(HttpStatusCode.OK, jsonResponse.status)
        assertEquals("application/json", jsonResponse.headers["Content-Type"])
        assertTrue(jsonResponse.bodyAsText().contains("JSON response"))

        // Test XML content negotiation
        val xmlResponse = client.get("http://localhost:8081/content-negotiation") {
            header("Accept", "application/xml")
        }
        assertEquals(HttpStatusCode.OK, xmlResponse.status)
        assertEquals("application/xml", xmlResponse.headers["Content-Type"])
        assertTrue(xmlResponse.bodyAsText().contains("XML response"))

        // Test default content negotiation
        val defaultResponse = client.get("http://localhost:8081/content-negotiation") {
            header("Accept", "text/html")
        }
        assertEquals(HttpStatusCode.OK, defaultResponse.status)
        assertEquals("text/plain", defaultResponse.headers["Content-Type"])
        assertTrue(defaultResponse.bodyAsText().contains("Plain text response"))
    }

    @Test
    fun testCustomHeaders() = runBlocking {
        val response = client.get("http://localhost:8081/custom-headers")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("custom-value", response.headers["X-Custom-Header"])
        assertEquals("12345", response.headers["X-Request-ID"])
        assertEquals("1000", response.headers["X-Rate-Limit"])
        assertEquals("999", response.headers["X-Rate-Limit-Remaining"])
    }

    @Test
    fun testHeaderValidation() = runBlocking {
        val response = client.get("http://localhost:8081/header-validation") {
            header("Authorization", "Bearer token123")
            header("Cookie", "session=abc123; user=john")
            header("User-Agent", "Mozilla/5.0 (Test Browser)")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Auth: Bearer token123"))
        assertTrue(body.contains("Cookie: session=abc123; user=john"))
        assertTrue(body.contains("UA: Mozilla/5.0 (Test Browser)"))
    }

    @Test
    fun testEmptyHeaders() = runBlocking {
        val response = client.get("http://localhost:8081/headers") {
            header("X-Empty", "")
            header("X-Whitespace", "   ")
        }
        val expected =
        """|Accept: */*
           |X-Whitespace: 
           |X-Empty: 
           |Accept-Charset: UTF-8
           |User-Agent: ktor-client
           |Host: localhost:8081""".trimMargin()

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(expected,body)
    }

    @Test
    // TODO this text is malformed. It changes along the way.
    fun testSpecialCharactersInHeaders() = runBlocking {
        val response = client.get("http://localhost:8081/headers") {
            header("X-Special", "value with spaces and symbols: !@#$%^&*()")
            header("X-Unicode", "测试中文")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        val expected = """|Accept: */*
        |X-Special: value with spaces and symbols: !@#$%^&*()
        |Accept-Charset: UTF-8
        |User-Agent: ktor-client
        |X-Unicode: æµè¯ä¸­æ
        |Host: localhost:8081""".trimMargin()
        assertEquals(expected,text)
    }

    @Test
    fun testLongHeaderValues() = runBlocking {
        val longValue = "a".repeat(1000)
        val response = client.get("http://localhost:8081/headers") {
            header("X-Long-Header", longValue)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("X-Long-Header: $longValue"))
    }

    @Test
    fun testManyHeaders() = runBlocking {
        val response = client.get("http://localhost:8081/headers") {
            repeat(50) { i ->
                header("X-Header-$i", "value-$i")
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        repeat(50) { i ->
            assertTrue(body.contains("X-Header-$i: value-$i"))
        }
    }
}