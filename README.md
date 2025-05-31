# VPN Application

This is an Android VPN application.

## Prerequisites

To build this project, you will need the following:

*   **Android SDK:** Version 34
    *   You can download this via Android Studio's SDK Manager.
*   **Java Development Kit (JDK):** Version 17
    *   Ensure your `JAVA_HOME` environment variable is set to the JDK 17 installation directory.
*   **Android NDK (Potentially):**
    *   The project includes the WireGuard library, which may have native components. Typically, Gradle's dependency management handles NDK downloads automatically if needed. If you encounter NDK-related build issues, ensure you have the NDK installed via Android Studio's SDK Manager.

## How to Compile

1.  **Clone the repository:**
    ```bash
    git clone <repository-url>
    cd <repository-directory>
    ```

2.  **Ensure Gradle wrapper has execute permissions (if necessary):**
    ```bash
    chmod +x ./gradlew
    ```

3.  **Build the APK:**
    *   To build a **debug** APK:
        ```bash
        ./gradlew assembleDebug
        ```
        The output APK will typically be located at `app/build/outputs/apk/debug/app-debug.apk`.

    *   To build a **release** APK (recommended for distribution):
        ```bash
        ./gradlew assembleRelease
        ```
        The output APK will typically be located at `app/build/outputs/apk/release/app-release.apk`.
        Note: For a release build, you would typically need to configure signing keys. This README assumes you have set up signing configurations in your `build.gradle` or will sign the APK manually after building. If signing is not configured, the `assembleRelease` command might produce an unsigned APK or fail.

## Running the Application

Once the APK is built, you can install it on an Android device or emulator using Android Debug Bridge (adb):

```bash
adb install path/to/your/app-release.apk
# or
adb install path/to/your/app-debug.apk
```
