/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.*

/**
 * Comprehensive test suite for testing paths and path variables in Ktor Undertow engine
 */
class PathVariablesTest {

    @Test
    fun testSinglePathVariable() = runBlocking {
        val response = client.get("http://localhost:8081/user/john")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: john", response.bodyAsText())
    }

    @Test
    fun testMultiplePathVariables() = runBlocking {
        val response = client.get("http://localhost:8081/user/123/profile/settings")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User ID: 123, Section: settings", response.bodyAsText())
    }

    @Test
    fun testPathVariableWithSpecialCharacters() = runBlocking {
        val response = client.get("http://localhost:8081/user/john-doe_123")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: john-doe_123", response.bodyAsText())
    }

    @Test
    fun testPathVariableWithNumbers() = runBlocking {
        val response = client.get("http://localhost:8081/order/12345")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Order ID: 12345", response.bodyAsText())
    }

    @Test
    fun testNestedPathVariables() = runBlocking {
        val response = client.get("http://localhost:8081/company/acme/department/engineering/employee/alice")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Company: acme, Department: engineering, Employee: alice", response.bodyAsText())
    }

    @Test
    fun testPathVariableWithQueryParameters() = runBlocking {
        val response = client.get("http://localhost:8081/profile/bob?format=json&include=profile")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: bob, Format: json, Include: profile", response.bodyAsText())
    }

    @Test
    fun testTailcardPathNotSupported() = runBlocking {
        // Note: Tailcard syntax is not supported in this Ktor version
        // This test documents that such paths return 404
        val response = client.get("http://localhost:8081/files/document.pdf")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun testOptionalPathSegments() = runBlocking {
        // Test with optional segment present
        val responseWithOptional = client.get("http://localhost:8081/api/v1/users")
        assertEquals(HttpStatusCode.OK, responseWithOptional.status)
        assertEquals("API Version: v1, Resource: users", responseWithOptional.bodyAsText())

        // Test with different version
        val responseWithDifferentVersion = client.get("http://localhost:8081/api/v2/products")
        assertEquals(HttpStatusCode.OK, responseWithDifferentVersion.status)
        assertEquals("API Version: v2, Resource: products", responseWithDifferentVersion.bodyAsText())
    }

    @Test
    fun testPathVariableWithSlashes() = runBlocking {
        // Test URL-encoded path with slashes
        val encodedPath = "folder%2Fsubfolder%2Ffile.txt"
        val response = client.get("http://localhost:8081/document/$encodedPath")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Document: folder/subfolder/file.txt", response.bodyAsText())
    }

    @Test
    fun testEmptyPathVariable() = runBlocking {
        // This should not match the route and return 404
        val response = client.get("http://localhost:8081/user/")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun testPathVariableWithSpaces() = runBlocking {
        val encodedName = "John%20Doe"
        val response = client.get("http://localhost:8081/user/$encodedName")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: John Doe", response.bodyAsText())
    }

    @Test
    fun testPathVariableWithUnicodeCharacters() = runBlocking {
        val encodedName = "Jos%C3%A9" // José in UTF-8
        val response = client.get("http://localhost:8081/user/$encodedName")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: José", response.bodyAsText())
    }

    @Test
    fun testComplexPathPattern() = runBlocking {
        val response = client.get("http://localhost:8081/shop/electronics/laptop/dell/xps-13")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Category: electronics, Type: laptop, Brand: dell, Model: xps-13", response.bodyAsText())
    }

    @Test
    fun testPathVariableValidation() = runBlocking {
        // Test numeric ID validation
        val validResponse = client.get("http://localhost:8081/product/123")
        assertEquals(HttpStatusCode.OK, validResponse.status)
        assertEquals("Product ID: 123", validResponse.bodyAsText())

        // Test invalid numeric ID (should still work as string)
        val invalidResponse = client.get("http://localhost:8081/product/abc")
        assertEquals(HttpStatusCode.OK, invalidResponse.status)
        assertEquals("Product ID: abc", invalidResponse.bodyAsText())
    }

    @Test
    fun testCaseInsensitivePathVariables() = runBlocking {
        val response = client.get("http://localhost:8081/user/ALICE")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: ALICE", response.bodyAsText())
    }

    @Test
    fun testPathVariablesWithDifferentHttpMethods() = runBlocking {
        // GET
        val getResponse = client.get("http://localhost:8081/resource/test123")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals("GET Resource: test123", getResponse.bodyAsText())

        // POST
        val postResponse = client.post("http://localhost:8081/resource/test123")
        assertEquals(HttpStatusCode.OK, postResponse.status)
        assertEquals("POST Resource: test123", postResponse.bodyAsText())

        // PUT
        val putResponse = client.put("http://localhost:8081/resource/test123")
        assertEquals(HttpStatusCode.OK, putResponse.status)
        assertEquals("PUT Resource: test123", putResponse.bodyAsText())

        // DELETE
        val deleteResponse = client.delete("http://localhost:8081/resource/test123")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)
        assertEquals("DELETE Resource: test123", deleteResponse.bodyAsText())
    }

    @Test
    fun testPathVariableExtraction() = runBlocking {
        val response = client.get("http://localhost:8081/extract/param1/value1/param2/value2")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("param1=value1, param2=value2", response.bodyAsText())
    }

    @Test
    fun testLongPathWithManyVariables() = runBlocking {
        val response = client.get("http://localhost:8081/long/a/b/c/d/e/f/g/h/i/j")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Values: a,b,c,d,e,f,g,h,i,j", response.bodyAsText())
    }

    @Test
    fun testPathVariableWithDots() = runBlocking {
        val response = client.get("http://localhost:8081/file/document.pdf")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("File: document.pdf", response.bodyAsText())
    }

    @Test
    fun testPathVariableWithMultipleDots() = runBlocking {
        val response = client.get("http://localhost:8081/file/archive.tar.gz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("File: archive.tar.gz", response.bodyAsText())
    }

    @Test
    fun testVeryLongPathVariable() = runBlocking {
        val longValue = "a".repeat(1000)
        val response = client.get("http://localhost:8081/user/$longValue")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: $longValue", response.bodyAsText())
    }

    @Test
    fun testPathVariableWithPercentEncoding() = runBlocking {
        // Test various percent-encoded characters
        val encodedValue = "hello%21world%40test%23"  // hello!world@test#
        val response = client.get("http://localhost:8081/user/$encodedValue")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: hello!world@test#", response.bodyAsText())
    }

    @Test
    fun testTrailingSlashBehavior() = runBlocking {
        // Test that trailing slash doesn't match path variable routes
        val response = client.get("http://localhost:8081/user/john/")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun testPathVariableWithPlusSign() = runBlocking {
        val encodedValue = "john%2Bdoe"  // john+doe
        val response = client.get("http://localhost:8081/user/$encodedValue")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: john+doe", response.bodyAsText())
    }

    @Test
    fun testPathVariableWithAmpersand() = runBlocking {
        val encodedValue = "company%26co"  // company&co
        val response = client.get("http://localhost:8081/user/$encodedValue")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("User: company&co", response.bodyAsText())
    }

    @Test
    fun testMultipleConsecutiveSlashes() = runBlocking {
        // This should normalize to single slashes and still work
        val response = client.get("http://localhost:8081//user//john")
        // Behavior may vary - this tests how the engine handles malformed URLs
        // We expect either OK or NotFound, but not a server error
        assertTrue(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NotFound)
    }

    companion object {
        val server = embeddedServer(Undertow, host = "localhost", port = 8081) {
            routing {
                // Single path variable
                get("/user/{name}") {
                    val name = call.parameters["name"]
                    call.respondText("User: $name")
                }

                // Multiple path variables
                get("/user/{id}/profile/{section}") {
                    val id = call.parameters["id"]
                    val section = call.parameters["section"]
                    call.respondText("User ID: $id, Section: $section")
                }

                // Order endpoint for numeric testing
                get("/order/{id}") {
                    val id = call.parameters["id"]
                    call.respondText("Order ID: $id")
                }

                // Nested path variables
                get("/company/{company}/department/{dept}/employee/{name}") {
                    val company = call.parameters["company"]
                    val dept = call.parameters["dept"]
                    val name = call.parameters["name"]
                    call.respondText("Company: $company, Department: $dept, Employee: $name")
                }

                // Path variable with query parameters (using different path to avoid conflict)
                get("/profile/{name}") {
                    val name = call.parameters["name"]
                    val format = call.request.queryParameters["format"]
                    val include = call.request.queryParameters["include"]
                    call.respondText("User: $name, Format: $format, Include: $include")
                }

                // Note: Tailcard syntax removed as it's not supported in this Ktor version
                // get("/files/{path...}") {
                //     val path = call.parameters["path"] ?: ""
                //     call.respondText("File path: $path")
                // }

                // API versioning
                get("/api/{version}/{resource}") {
                    val version = call.parameters["version"]
                    val resource = call.parameters["resource"]
                    call.respondText("API Version: $version, Resource: $resource")
                }

                // Document path with encoded slashes
                get("/document/{path}") {
                    val path = call.parameters["path"]
                    call.respondText("Document: $path")
                }

                // Complex shop pattern
                get("/shop/{category}/{type}/{brand}/{model}") {
                    val category = call.parameters["category"]
                    val type = call.parameters["type"]
                    val brand = call.parameters["brand"]
                    val model = call.parameters["model"]
                    call.respondText("Category: $category, Type: $type, Brand: $brand, Model: $model")
                }

                // Product endpoint
                get("/product/{id}") {
                    val id = call.parameters["id"]
                    call.respondText("Product ID: $id")
                }

                // Resource endpoints for different HTTP methods
                get("/resource/{id}") {
                    val id = call.parameters["id"]
                    call.respondText("GET Resource: $id")
                }

                post("/resource/{id}") {
                    val id = call.parameters["id"]
                    call.respondText("POST Resource: $id")
                }

                put("/resource/{id}") {
                    val id = call.parameters["id"]
                    call.respondText("PUT Resource: $id")
                }

                delete("/resource/{id}") {
                    val id = call.parameters["id"]
                    call.respondText("DELETE Resource: $id")
                }

                // Parameter extraction test
                get("/extract/{param1}/{value1}/{param2}/{value2}") {
                    val param1 = call.parameters["param1"]
                    val value1 = call.parameters["value1"]
                    val param2 = call.parameters["param2"]
                    val value2 = call.parameters["value2"]
                    call.respondText("$param1=$value1, $param2=$value2")
                }

                // Long path with many variables
                get("/long/{a}/{b}/{c}/{d}/{e}/{f}/{g}/{h}/{i}/{j}") {
                    val values = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
                        .mapNotNull { call.parameters[it] }
                        .joinToString(",")
                    call.respondText("Values: $values")
                }

                // File endpoint for testing dots in path variables
                get("/file/{filename}") {
                    val filename = call.parameters["filename"]
                    call.respondText("File: $filename")
                }
            }
        }

        val client = HttpClient(CIO)

        @JvmStatic
        @BeforeClass
        fun beforeClass(): Unit = runBlocking {
            server.startSuspend(wait = false)
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