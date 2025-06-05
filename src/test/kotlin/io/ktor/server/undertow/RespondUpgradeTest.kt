/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.junit.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.*
import java.io.*
import java.util.concurrent.*
import kotlin.coroutines.CoroutineContext

class RespondUpgradeTest {

    private lateinit var server: EmbeddedServer<UndertowApplicationEngine, UndertowApplicationEngine.Configuration>
    private lateinit var client: HttpClient
    private var port = 8081

    @Before
    fun setup() {
        client = HttpClient(CIO)
    }

    @After
    fun teardown() = runBlocking {
        try {
            client.close()
            if (::server.isInitialized) {
                server.stopSuspend()
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    @Test(timeout = 10000) // 10 second timeout
    fun testBasicProtocolUpgrade() = runBlocking {
        port = 8081
        val upgradeCompleted = CompletableDeferred<Boolean>()

        server = embeddedServer(Undertow, host = "localhost", port = port) {
            routing {
                get("/upgrade") {
                    val upgrade = object : OutgoingContent.ProtocolUpgrade() {
                        override val headers: Headers = headersOf(
                            HttpHeaders.Upgrade to listOf("test-protocol"),
                            HttpHeaders.Connection to listOf("upgrade")
                        )

                        override suspend fun upgrade(
                            input: ByteReadChannel,
                            output: ByteWriteChannel,
                            engineContext: CoroutineContext,
                            userContext: CoroutineContext
                        ): Job {
                            return launch(engineContext) {
                                try {
                                    // Simple upgrade completion - complete immediately
                                    upgradeCompleted.complete(true)
                                } catch (e: Exception) {
                                    upgradeCompleted.completeExceptionally(e)
                                }
                            }
                        }
                    }
                    call.respond(upgrade)
                }
            }
        }

        server.startSuspend(wait = false)
        delay(500) // Give server time to start

        try {
            // Test with raw socket connection
            Socket("localhost", port).use { socket ->
            socket.soTimeout = 5000 // 5 second timeout
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Send HTTP upgrade request
            writer.println("GET /upgrade HTTP/1.1")
            writer.println("Host: localhost:$port")
            writer.println("Connection: upgrade")
            writer.println("Upgrade: test-protocol")
            writer.println()

            // Read response headers
            val statusLine = reader.readLine()
            assertTrue(statusLine.contains("101"), "Expected 101 Switching Protocols, got: $statusLine")

            // Read headers until empty line
            var line: String?
            var foundUpgradeHeader = false
            var foundConnectionHeader = false

            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.startsWith("Upgrade:")) {
                    foundUpgradeHeader = true
                    assertTrue(line!!.contains("test-protocol"))
                }
                if (line!!.startsWith("Connection:")) {
                    foundConnectionHeader = true
                    assertTrue(line!!.lowercase().contains("upgrade"))
                }
            }

            assertTrue(foundUpgradeHeader, "Upgrade header not found")
            assertTrue(foundConnectionHeader, "Connection header not found")

            // Wait for upgrade to complete with timeout
            withTimeout(3000) {
                val result = upgradeCompleted.await()
                assertTrue(result, "Upgrade should complete successfully")
            }
            }
        } finally {
            server.stopSuspend()
        }
    }

    @Test(timeout = 10000)
    fun testUpgradeWithCustomHeaders() = runBlocking {
        port = 8082
        val upgradeCompleted = CompletableDeferred<Boolean>()

        server = embeddedServer(Undertow, host = "localhost", port = port) {
            routing {
                get("/upgrade-custom") {
                    val upgrade = object : OutgoingContent.ProtocolUpgrade() {
                        override val headers: Headers = headersOf(
                            HttpHeaders.Upgrade to listOf("websocket"),
                            HttpHeaders.Connection to listOf("upgrade"),
                            "Sec-WebSocket-Accept" to listOf("test-accept-key"),
                            "Sec-WebSocket-Protocol" to listOf("chat")
                        )

                        override suspend fun upgrade(
                            input: ByteReadChannel,
                            output: ByteWriteChannel,
                            engineContext: CoroutineContext,
                            userContext: CoroutineContext
                        ): Job {
                            return launch(engineContext) {
                                upgradeCompleted.complete(true)
                            }
                        }
                    }
                    call.respond(upgrade)
                }
            }
        }

        server.startSuspend(wait = false)
        delay(500)

        try {
            Socket("localhost", port).use { socket ->
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println("GET /upgrade-custom HTTP/1.1")
                writer.println("Host: localhost:$port")
                writer.println("Connection: upgrade")
                writer.println("Upgrade: websocket")
                writer.println()

                val statusLine = reader.readLine()
                assertTrue(statusLine.contains("101"))

                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val parts = line!!.split(":", limit = 2)
                    if (parts.size == 2) {
                        headers[parts[0].trim()] = parts[1].trim()
                    }
                }

                assertEquals("websocket", headers["Upgrade"])
                assertEquals("Upgrade", headers["Connection"])
                assertEquals("test-accept-key", headers["Sec-WebSocket-Accept"])
                assertEquals("chat", headers["Sec-WebSocket-Protocol"])

                withTimeout(3000) {
                    assertTrue(upgradeCompleted.await())
                }
            }
        } finally {
            server.stopSuspend()
        }
    }

    @Test(timeout = 10000)
    fun testUpgradeErrorHandling() = runBlocking {
        port = 8083
        val upgradeStarted = CompletableDeferred<Boolean>()

        server = embeddedServer(Undertow, host = "localhost", port = port) {
            routing {
                get("/upgrade-error") {
                    val upgrade = object : OutgoingContent.ProtocolUpgrade() {
                        override val headers: Headers = headersOf(
                            HttpHeaders.Upgrade to listOf("test-protocol"),
                            HttpHeaders.Connection to listOf("upgrade")
                        )

                        override suspend fun upgrade(
                            input: ByteReadChannel,
                            output: ByteWriteChannel,
                            engineContext: CoroutineContext,
                            userContext: CoroutineContext
                        ): Job {
                            return launch(engineContext) {
                                upgradeStarted.complete(true)
                                throw RuntimeException("Simulated upgrade error")
                            }
                        }
                    }
                    call.respond(upgrade)
                }
            }
        }

        server.startSuspend(wait = false)
        delay(500)

        try {
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 5000 // 5 second timeout
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println("GET /upgrade-error HTTP/1.1")
                writer.println("Host: localhost:$port")
                writer.println("Connection: upgrade")
                writer.println("Upgrade: test-protocol")
                writer.println()

                val statusLine = reader.readLine()
                assertTrue(statusLine.contains("101"))

                // Skip headers
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    // Skip headers
                }

                // Verify upgrade started (even though it will error)
                withTimeout(3000) {
                    assertTrue(upgradeStarted.await())
                }
            }
        } finally {
            server.stopSuspend()
        }
    }

    @Test(timeout = 15000)
    fun testBidirectionalCommunication() = runBlocking {
        port = 8084
        val messagesReceived = mutableListOf<String>()
        val communicationComplete = CompletableDeferred<Boolean>()

        server = embeddedServer(Undertow, host = "localhost", port = port) {
            routing {
                get("/echo-upgrade") {
                    val upgrade = object : OutgoingContent.ProtocolUpgrade() {
                        override val headers: Headers = headersOf(
                            HttpHeaders.Upgrade to listOf("echo-protocol"),
                            HttpHeaders.Connection to listOf("upgrade")
                        )

                        override suspend fun upgrade(
                            input: ByteReadChannel,
                            output: ByteWriteChannel,
                            engineContext: CoroutineContext,
                            userContext: CoroutineContext
                        ): Job {
                            return launch(engineContext) {
                                try {
                                    repeat(3) {
                                        val buffer = ByteArray(1024)
                                        val bytesRead = input.readAvailable(buffer)
                                        if (bytesRead > 0) {
                                            val message = String(buffer, 0, bytesRead)
                                            messagesReceived.add(message)

                                            // Echo back with prefix
                                            output.writeStringUtf8("Echo: $message")
                                            output.flush()
                                        } else {
                                            delay(10) // Small delay if no data available
                                        }
                                    }
                                    communicationComplete.complete(true)
                                } catch (e: Exception) {
                                    communicationComplete.completeExceptionally(e)
                                }
                            }
                        }
                    }
                    call.respond(upgrade)
                }
            }
        }

        server.startSuspend(wait = false)
        delay(500)

        try {
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 10000 // 10 second timeout
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Send upgrade request
                writer.println("GET /echo-upgrade HTTP/1.1")
                writer.println("Host: localhost:$port")
                writer.println("Connection: upgrade")
                writer.println("Upgrade: echo-protocol")
                writer.println()

                // Read response
                val statusLine = reader.readLine()
                assertTrue(statusLine.contains("101"))

                // Skip headers
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    // Skip headers
                }

                val outputStream = socket.getOutputStream()

                // Send multiple messages
                val testMessages = listOf("Hello", "World", "Test")
                testMessages.forEach { message ->
                    outputStream.write(message.toByteArray())
                    outputStream.flush()
                    delay(100) // Small delay between messages
                }

                // Wait for communication to complete with timeout
                withTimeout(10000) {
                    assertTrue(communicationComplete.await())
                    assertEquals(testMessages, messagesReceived)
                }
            }
        } finally {
            server.stopSuspend()
        }
    }

    @Test(timeout = 10000)
    fun testUpgradeWithEmptyHeaders() = runBlocking {
        port = 8085
        val upgradeCompleted = CompletableDeferred<Boolean>()

        server = embeddedServer(Undertow, host = "localhost", port = port) {
            routing {
                get("/upgrade-empty") {
                    val upgrade = object : OutgoingContent.ProtocolUpgrade() {
                        override val headers: Headers = Headers.Empty

                        override suspend fun upgrade(
                            input: ByteReadChannel,
                            output: ByteWriteChannel,
                            engineContext: CoroutineContext,
                            userContext: CoroutineContext
                        ): Job {
                            return launch(engineContext) {
                                upgradeCompleted.complete(true)
                            }
                        }
                    }
                    call.respond(upgrade)
                }
            }
        }

        server.startSuspend(wait = false)
        delay(500)

        try {
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 5000 // 5 second timeout
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println("GET /upgrade-empty HTTP/1.1")
                writer.println("Host: localhost:$port")
                writer.println("Connection: upgrade")
                writer.println("Upgrade: test-protocol")
                writer.println()

                val statusLine = reader.readLine()
                assertTrue(statusLine.contains("101"))

                withTimeout(3000) {
                    assertTrue(upgradeCompleted.await())
                }
            }
        } finally {
            server.stopSuspend()
        }
    }

    @Test(timeout = 10000)
    fun testMultipleUpgradeHeaders() = runBlocking {
        port = 8086
        val upgradeCompleted = CompletableDeferred<Boolean>()

        server = embeddedServer(Undertow, host = "localhost", port = port) {
            routing {
                get("/upgrade-multiple") {
                    val upgrade = object : OutgoingContent.ProtocolUpgrade() {
                        override val headers: Headers = headersOf(
                            HttpHeaders.Upgrade to listOf("websocket"),
                            HttpHeaders.Connection to listOf("upgrade", "keep-alive"),
                            "Custom-Header" to listOf("value1", "value2", "value3")
                        )

                        override suspend fun upgrade(
                            input: ByteReadChannel,
                            output: ByteWriteChannel,
                            engineContext: CoroutineContext,
                            userContext: CoroutineContext
                        ): Job {
                            return launch(engineContext) {
                                upgradeCompleted.complete(true)
                            }
                        }
                    }
                    call.respond(upgrade)
                }
            }
        }

        server.startSuspend(wait = false)
        delay(500)

        try {
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 5000 // 5 second timeout
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println("GET /upgrade-multiple HTTP/1.1")
                writer.println("Host: localhost:$port")
                writer.println("Connection: upgrade")
                writer.println("Upgrade: websocket")
                writer.println()

                val statusLine = reader.readLine()
                assertTrue(statusLine.contains("101"))

                val headers = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    headers.add(line!!)
                }

                // Check that multiple header values are properly set
                val customHeaders = headers.filter { it.startsWith("Custom-Header:") }
                assertTrue(customHeaders.isNotEmpty(), "Custom headers should be present")

                withTimeout(3000) {
                    assertTrue(upgradeCompleted.await())
                }
            }
        } finally {
            server.stopSuspend()
        }
    }
}