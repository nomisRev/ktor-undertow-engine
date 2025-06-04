/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.undertow

import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.engine.*

/**
 * An [ApplicationEngineFactory] providing an Undertow-based [ApplicationEngine]
 */
public object Undertow : ApplicationEngineFactory<UndertowApplicationEngine, UndertowApplicationEngine.Configuration> {

    override fun configuration(
        configure: UndertowApplicationEngine.Configuration.() -> Unit
    ): UndertowApplicationEngine.Configuration {
        return UndertowApplicationEngine.Configuration().apply(configure)
    }

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: UndertowApplicationEngine.Configuration,
        applicationProvider: () -> Application
    ): UndertowApplicationEngine {
        return UndertowApplicationEngine(environment, monitor, developmentMode, configuration, applicationProvider)
    }
}