# Fix Gradle Sync and Connection Issues

The project is experiencing intermittent Gradle failures and incomplete Android Studio synchronization due to overly aggressive network timeouts and stale Gradle metadata from a different version (9.6.1).

## User Review Required

> [!IMPORTANT]
> The project contains a `.gradle/9.6.1` directory and a `gradle/gradle-daemon-jvm.properties` file, both of which indicate this project was recently opened or built with a newer (possibly experimental/internal) Gradle version. The `gradle-wrapper.properties` is currently set to `8.7`. I will clean up the stale metadata to ensure a consistent environment for Gradle 8.7.

## Proposed Changes

### Gradle Configuration

#### [MODIFY] [gradle-wrapper.properties](file:///Users/sanleesam/Desktop/DropFlow/apps/android/gradle/wrapper/gradle-wrapper.properties)
- Increase `networkTimeout` from 10s to 60s to prevent `SocketTimeoutException`.
- Increase `retries` from 0 to 3 to handle transient network issues.

#### [DELETE] [gradle-daemon-jvm.properties](file:///Users/sanleesam/Desktop/DropFlow/apps/android/gradle/gradle-daemon-jvm.properties)
- This file is used by Gradle 8.8+ for the "Daemon JVM" feature. Since the project is targeting Gradle 8.7, this file is unnecessary and may cause confusion for tools.

### IDE Configuration

#### [MODIFY] [gradle.xml](file:///Users/sanleesam/Desktop/DropFlow/apps/android/.idea/gradle.xml)
- Explicitly set `distributionType` to `DEFAULT_WRAPPED`.
- Ensure the project and `:app` modules are correctly registered in the IDE model.

### Cleanup

#### [DELETE] [stale .gradle directory](file:///Users/sanleesam/Desktop/DropFlow/apps/android/.gradle/9.6.1)
- Remove the metadata from Gradle 9.6.1 to avoid conflicts with the 8.7 distribution.

## Verification Plan

### Automated Tests
- Run `./gradlew --version` to verify the wrapper can successfully download and initialize Gradle 8.7.
- Run `./gradlew clean build` to ensure the project builds correctly from the command line.

### Manual Verification
- Perform a "Sync Project with Gradle Files" in Android Studio.
- Verify that the `app` run configuration is automatically generated and detected.
