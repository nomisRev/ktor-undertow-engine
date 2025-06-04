# Ktor "Why did you stop?" Feature

This project implements the "Why did you stop?" feature for Ktor servers, providing detailed information about server shutdown causes.

## Features

Here's a list of features included in this project:

| Name                                               | Description                                                 |
|----------------------------------------------------|-------------------------------------------------------------|
| [Routing](https://start.ktor.io/p/routing-default) | Allows to define structured routes and associated handlers. |
| "Why did you stop?" Feature                        | Provides detailed information about server shutdown causes. |

## "Why did you stop?" Feature

The "Why did you stop?" feature provides detailed information about why a server shutdown occurred. This information is helpful for debugging and monitoring server behavior in various environments.

Key aspects of this feature:

- **Automatic detection of shutdown causes**: The feature automatically detects common shutdown scenarios including:
  - JVM shutdown hooks (CTRL+C, process termination)
  - Test completion
  - Kubernetes/container orchestration signals
  - Uncaught exceptions
  - API-triggered shutdowns
  - Manual shutdown requests

- **Detailed logging**: The feature logs detailed information about the shutdown process, including:
  - The specific cause of the shutdown
  - Server uptime at shutdown
  - Timing of the shutdown phases
  - Component state during shutdown

- **Global shutdown reason tracking**: Applications can access shutdown information from anywhere:
  - `ShutdownReasonTracker.getReason()`: Get the current shutdown reason
  - `ShutdownReasonTracker.getStatus()`: Get the current server status
  - `ShutdownReasonTracker.setReason(reason)`: Set a custom shutdown reason

- **Event notifications**: Applications can subscribe to shutdown events:
  - `ApplicationStopPreparing`: Fired at the beginning of the shutdown process
  - `ApplicationStopped`: Fired when the shutdown is complete

### Demo Endpoints

The project includes several endpoints to demonstrate the "Why did you stop?" feature:

- `/why-did-you-stop`: Documentation about the feature
- `/stop`: Initiates a clean shutdown to demonstrate the feature
- `/crash`: Demonstrates uncaught exception detection
- `/shutdown-info`: Shows current server status and uptime

### Usage Example

```kotlin
// Initialize the shutdown reason tracker
ShutdownReasonTracker.initialize()

// Subscribe to shutdown events
environment.monitor.subscribe(ApplicationStopping) {
    log.info("Server is stopping: ${ShutdownReasonTracker.getReason()}")
}

// Set a custom shutdown reason
ShutdownReasonTracker.setReason("Database connection lost")

// Trigger a shutdown
environment.monitor.raise(ApplicationStopping, application)
```

## Implementation Details

The feature works by:

1. Monitoring application lifecycle events via `environment.monitor`
2. Analyzing stack traces during shutdown to determine the cause
3. Using a global `ShutdownReasonTracker` to store and retrieve shutdown information
4. Tracking server uptime from startup to shutdown
5. Providing detailed logs throughout the shutdown process

## Building & Running

To build or run the project, use one of the following tasks:

| Task                          | Description                                                          |
|-------------------------------|----------------------------------------------------------------------|
| `./gradlew test`              | Run the tests                                                        |
| `./gradlew build`             | Build everything                                                     |
| `buildFatJar`                 | Build an executable JAR of the server with all dependencies included |
| `buildImage`                  | Build the docker image to use with the fat JAR                       |
| `publishImageToLocalRegistry` | Publish the docker image locally                                     |
| `run`                         | Run the server                                                       |
| `runDocker`                   | Run using the local docker image                                     |

When the server starts, you'll see:

```
INFO  Application - 'Why did you stop?' feature is active and tracking shutdown causes
INFO  Application - Application started in 0.303 seconds.
INFO  Application - Responding at http://0.0.0.0:8080
```

When the server shuts down, you'll see:

```
INFO  Application - Why did you stop? JVM shutdown hook triggered (likely CTRL+C or system shutdown)
INFO  Application - Server uptime at shutdown: 2 minutes, 45 seconds
INFO  Application - Application has stopped completely
```

