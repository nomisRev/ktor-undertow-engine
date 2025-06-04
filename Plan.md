**Development Plan: Ktor Undertow `BaseApplicationEngine`**
**Goal:** Implement a Ktor using Undertow as the underlying HTTP server, drawing inspiration from the module for structure and best practices. `BaseApplicationEngine``io.ktor.server.netty`
**Phase 1: Project Setup and Core Engine Structure**
1. **Module and Dependencies:**
    - **Action:** Create a new Gradle or Maven module (e.g., `ktor-server-undertow`).
    - **Action:** Add necessary dependencies:
        - `io.ktor:ktor-server-core`: For , , and other Ktor server APIs. `BaseApplicationEngine``ApplicationCall`
        - `io.undertow:undertow-core`: For the Undertow server implementation.

    - **Inspiration:** Review the file in the `ktor-server-netty` module for dependency setup. `build.gradle.kts`

2. **`UndertowApplicationEngine` Class:**
    - **Action:** Create the main engine class `UndertowApplicationEngine.kt`.
    - **Action:** This class will extend `io.ktor.server.engine.BaseApplicationEngine`.
``` kotlin
        // package io.ktor.server.undertow
        // import io.ktor.server.engine.*
        // class UndertowApplicationEngine(
        //     environment: ApplicationEngineEnvironment,
        //     configure: Configuration.() -> Unit
        // ) : BaseApplicationEngine(environment, configure) {
        //     class Configuration : BaseApplicationEngine.Configuration() {
        //         // Undertow-specific configurations can be added here
        //         var workerThreads: Int = Math.max(2, Runtime.getRuntime().availableProcessors() - 1) * 2 // Example
        //     }
        //
        //     // ... more implementation later
        // }
```
- **Action:** Implement the nested class, inheriting from , to hold both common and Undertow-specific settings (e.g., worker thread count, buffer sizes). `Configuration``BaseApplicationEngine.Configuration`
- **Inspiration:** and its class. `io.ktor.server.netty.NettyApplicationEngine``Configuration`
    1. **Server Initialization and Lifecycle ( method):`start`**
        - **Action:** Override the `start(wait: Boolean): UndertowApplicationEngine` method.
        - **Action:** Inside :
            1. Instantiate `io.undertow.Undertow.Builder`.
            2. Configure Undertow listeners (HTTP/HTTPS) based on `environment.connectors` and the engine's . For each connector:
                - Use `builder.addHttpListener(connector.port, connector.host)` for HTTP.
                - For HTTPS, configure `SSLContext` and apply it to the listener.

`configuration`
3. Set Undertow options (e.g., worker threads from `configuration.workerThreads`) using `builder.setWorkerThreads()` and other relevant `UndertowOptions`.
4. Define and set the root `io.undertow.server.HttpHandler` (details in Phase 2). This handler will bridge to Ktor.
5. Build the Undertow server: `val server = builder.build()`. Store this instance.
6. Start the server: `server.start()`.
7. Invoke `environment.monitor.raise(ApplicationStarting, environment)` before starting and `environment.monitor.raise(ApplicationStarted, environment)` after.
8. Handle the parameter, potentially by blocking the current thread if `true` (though typically Ktor applications run in a non-blocking way and `application.join()` is used elsewhere if needed). `wait``start`

`start`
- **Inspiration:** `NettyApplicationEngine.start()` for server setup, connector configuration, and lifecycle event management.

    2. **Server Shutdown ( method):`stop`**
        - **Action:** Override the method. `stop(gracePeriodMillis: Long, timeoutMillis: Long)`
        - **Action:** Inside :
            1. Raise `environment.monitor.raise(ApplicationStopPreparing, environment)`.
            2. Stop the Undertow server instance (e.g., `server?.stop()`). Consider Undertow's graceful shutdown options.
            3. Clean up any resources (e.g., custom worker pools if created).
            4. Call `super.stop(gracePeriodMillis, timeoutMillis)` which handles `environment.monitor.raise(ApplicationStopped, this)`.

`stop`
- **Inspiration:** `NettyApplicationEngine.stop()` for graceful shutdown procedures.

**Phase 2: Request Processing and Ktor Integration**
1. **Root for Ktor:`HttpHandler`**
- **Action:** Create an implementation (e.g., `KtorUndertowHttpHandler`) that will be the main entry point from Undertow into Ktor. `HttpHandler`
- **Action:** In its `handleRequest(exchange: io.undertow.server.HttpServerExchange)` method:
1. Convert the `HttpServerExchange` into a Ktor (details below). `ApplicationCall`
2. Process the call using the Ktor pipeline: `pipeline.execute(applicationCall)`.

            - **Important:** `HttpServerExchange.dispatch()` might be needed if Ktor's pipeline execution can block, to move the execution off Undertow's I/O threads. Ktor's pipeline is suspending, so it should integrate well with Undertow's non-blocking model if handled correctly within a coroutine context.

    2. **`UndertowApplicationCall` Implementation:**
        - **Action:** Create a class `UndertowApplicationCall` that implements `io.ktor.server.application.ApplicationCall`.
        - **Action:** This class will wrap `HttpServerExchange` and provide Ktor-specific views on the request and response.
        - **Request Mapping:**
            - HTTP Method: `exchange.requestMethod.toString()`
            - URI & Query Parameters: `exchange.requestURI`, `exchange.queryString`, `exchange.queryParameters`
            - Headers: `exchange.requestHeaders` (convert `HeaderMap` to Ktor's `Headers`)
            - Request Body: Create an . Adapt `exchange.getInputStream()` to a `ByteReadChannel`. `ApplicationReceivePipeline`
            - Connection details: Remote host, local host, scheme.

        - **Response Mapping (within 's `response` object):`ApplicationCall`**
            - Status Code: Set `exchange.statusCode` from `call.response.status()`.
            - Headers: Set `exchange.responseHeaders` from `call.response.headers`.
            - Response Body: Adapt Ktor's to write to `exchange.getOutputStream()` or use `exchange.getResponseSender()`. Handle different types like `OutgoingContent.ByteArrayContent`, `OutgoingContent.ReadChannelContent`, etc. Ensure `exchange.endExchange()` is called after the response is fully sent. `OutgoingContent`

        - **Inspiration:** for how it adapts Netty's request/response objects and context to . `NettyApplicationCall``ApplicationCall`

**Phase 3: Engine Factory and Advanced Features**
1. **`UndertowEngineFactory`:**
- **Action:** Create an object `Undertow` that implements `ApplicationEngineFactory<UndertowApplicationEngine, UndertowApplicationEngine.Configuration>`.
``` kotlin
        // package io.ktor.server.undertow
        // object Undertow : ApplicationEngineFactory<UndertowApplicationEngine, UndertowApplicationEngine.Configuration> {
        //     override fun create(
        //         environment: ApplicationEngineEnvironment,
        //         configure: UndertowApplicationEngine.Configuration.() -> Unit
        //     ): UndertowApplicationEngine {
        //         return UndertowApplicationEngine(environment, configure)
        //     }
        // }
```
- **Action:** Implement the method to instantiate and return `UndertowApplicationEngine`. `create`
- **Inspiration:** object in `ktor-server-netty`. `Netty`
    1. **HTTPS/SSL Configuration Deep Dive:**
        - **Action:** Add detailed SSL configuration options to `UndertowApplicationEngine.Configuration` (e.g., key store path, key store password, key alias, trust store if needed).
        - **Action:** In `UndertowApplicationEngine.start()`, when an HTTPS connector is configured:
            1. Load the KeyStore.
            2. Initialize `KeyManagerFactory` and `TrustManagerFactory` (if applicable).
            3. Create an `SSLContext` and initialize it with the key/trust managers.
            4. Apply this `SSLContext` to the Undertow HTTPS listener (e.g., `builder.addHttpsListener(port, host, sslContext)`).

        - **Inspiration:** How configures for Netty. `NettyApplicationEngine``SslContextFactory`

    2. **WebSocket Support (Optional but Recommended):**
        - **Action:** Research Undertow's WebSocket API (`io.undertow.websockets.core.*` and `io.undertow.websockets.spi.WebSocketHttpExchange`).
        - **Action:** Modify the root . If a request is a WebSocket upgrade request:
            1. Use Undertow's WebSocket handshake mechanism.
            2. Create an Undertow `WebSocketConnectionCallback`.
            3. Inside the callback's `onConnect(exchange: WebSocketHttpExchange, channel: WebSocketChannel)`:
                - Bridge the `WebSocketChannel` to a Ktor `DefaultWebSocketSession` (or a custom implementation).
                - Map incoming Undertow WebSocket frames (text, binary, ping, pong, close) to Ktor WebSocket events/frames and vice-versa for outgoing frames.
                - Handle Ktor's `send()` and `incoming` channel for the WebSocket session.

`HttpHandler`
- **Inspiration:** 's WebSocket handling, specifically how it integrates Netty's WebSocket events with Ktor's . `NettyApplicationEngine``WebSocketSession`

    3. **Comprehensive Error Handling:**
        - **Action:** Ensure that exceptions during request processing (both in Undertow and Ktor pipeline) are caught.
        - **Action:** Log errors using `environment.log`.
        - **Action:** Send appropriate HTTP error responses if an error occurs before the response is committed. If an error occurs during response streaming, try to close the connection gracefully.

    4. **Testing Strategy:**
        - **Action:** Write unit tests for discrete parts, like the `UndertowApplicationCall` request/response mapping.
        - **Action:** Implement integration tests using Ktor's utility, configuring it to use the new `Undertow` engine. Test various Ktor features:
            - Basic GET, POST requests.
            - Header handling.
            - Query parameters.
            - Path parameters.
            - Different response types.
            - Error handling.
            - If implemented, WebSocket communication.

`testApplication`
- **Inspiration:** The test suites within `ktor-server-netty` and other Ktor engine modules.

**Key Technical Considerations:**
- **Threading Model and Coroutines:**
- Undertow uses XNIO worker threads. Ktor is coroutine-based.
- Ensure that blocking Ktor operations (if any, though typically avoided in Ktor handlers) or long-running computations within the Ktor pipeline are dispatched appropriately, possibly using `HttpServerExchange.dispatch()` to move execution from an I/O thread to a worker thread if the Ktor pipeline doesn't handle this transparently with its own coroutine dispatchers. Ktor's suspending handlers should integrate well if launched in the correct coroutine context.

    - **Buffer Management:**
        - Pay attention to Undertow's buffer pooling () for efficient memory usage, especially for request and response bodies. `ByteBufferPool`

    - **Backpressure:**
        - For streaming request/response bodies, ensure backpressure is handled correctly between Undertow's channels and Ktor's /. `ByteReadChannel``ByteWriteChannel`

    - **HTTP/2 Support:**
        - Undertow supports HTTP/2. Plan for enabling and configuring this in the `UndertowApplicationEngine.Configuration` and `Undertow.Builder` as a future enhancement or part of the initial scope if desired.

This plan should provide a solid foundation for developing the Undertow engine. Each phase and action item can be broken down further as development progresses. Good luck!
