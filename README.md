# Xiaomi AI Button Remapper

Give the physical AI button on a Xiaomi 18 Pro a camera shortcut, an app launcher, or a different action for each gesture. The initial mapping opens the camera on a single press. Remapping starts switched off.

The target is the **Linux** key `KEY_MACRO_RECORD_STOP` (689, hex `0x2B1`) on a physical `gpio-keys` device. The owner observed DOWN/UP on a Xiaomi 18 Pro and suppression using Key Mapper Expert Mode. This project's own HyperOS suppression, camera launch, and power/volume behavior still require a handset test. Support for other models depends on their input device, kernel, key layout, and shell permissions.

<img src="docs/screenshots/button-light.png" alt="The button overview with single press assigned to Open camera" width="230"> <img src="docs/screenshots/picker-dark.png" alt="The action picker in dark mode" width="230">

## Install and try the camera mapping

1. Download the [preview APK](https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases/download/v0.1.0-preview.1/Xiaomi-AI-Button-Remapper-0.1.0-debug.apk), or get **Xiaomi-AI-Button-Remapper-debug** from a successful [GitHub Actions build](https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/actions/workflows/android.yml). The preview is a direct APK download. The Actions artifact requires GitHub sign-in and contains the APK in a ZIP. Sideload the APK on an ARM64 Android 11+ device. Android 17 is the build target. An x86-64 build is included for development.
2. Install Shizuku and start its service using wireless debugging or USB debugging. Use Shizuku's ADB mode on a stock, unrooted phone. [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/)
3. In the remapper's **Diagnostics**, grant Shizuku access. Allow notifications so the ongoing session and its Stop action are accessible. Start the live listener with remapping off. Press and release the AI button. Xiaomi's action can open during this passive check. The notification stays visible and the listener records for up to 60 seconds, so you can return to Diagnostics and inspect the release.
4. Stop the listener, leave **Single press** set to **Open camera**, release all side buttons, then switch on **Remap button**. In Diagnostics, check the helper is active. If setup is refused, read the reason before retrying.
5. Press once with the phone unlocked. Check for one camera launch and no Xiaomi assistant. Immediately test volume up/down, power/lock, and power/wake. **Emergency disable** in Diagnostics or **Stop** in the notification releases the session. Holding the AI key for eight seconds also ends an active grab.

## Actions and timing

| Control | Behavior |
| --- | --- |
| Single, double, long, triple | Each gesture can have its own action. With just the default mapping, single press fires on release without a double-tap delay. Assign a multi-press action to enable its recognition window. |
| Camera and flashlight | Camera uses the system camera intent. The locked path requests the secure camera. Torch availability depends on the camera hardware and its current use. |
| Applications | Choose an installed application with a launchable activity in the current Android user profile. |
| Shortcuts | Choose from apps publishing a shortcut creator. Android also exposes dynamic, manifest and pinned shortcuts to callers with shortcut-host permission. The picker shows what the system makes available. |
| System settings | Choose a Settings destination, such as Wi-Fi or Display. The picker shows destinations that have a handler on this Android user profile. |
| Silent and Do Not Disturb | Grant notification policy access in Settings where required. On newer Android versions, DND changes operate through the app's rule and other active rules can keep DND on. |
| URL and custom intent | Configure a web URL or a supported Android activity intent URI. External targets retain Android's normal permissions, keyguard and background launch rules. |
| Permitted shell command | Pick a specific allowed command, including media controls and device information. The helper validates the exact command and executes an argument array. |
| Disable or restore | For a disabled button, set all four actions to **No action** and turn remapping on. For the native Xiaomi behavior, turn remapping off or choose **Restore Xiaomi default**. Restoring releases the entire device and retains your mappings. |

**Settings** includes the multi-press interval, long-press threshold, debounce, haptic feedback and configuration backup. Export writes a versioned JSON file to a location you select. An import previews the actions and leaves remapping off until you enable it. Exported URLs, app choices and intent data are readable in that file. Settings live in app-private DataStore. The APK requests no network permission and contains no telemetry.

## Background operation and recovery

The settings activity, the notification service, the Shizuku server and the input helper have separate lifetimes. Closing the settings screen leaves the active foreground service running. The app declares Android's **specialUse** foreground service type for user-enabled hardware remapping and displays a stop control. On HyperOS, allow notifications for the app. If HyperOS terminates a session, look under **Settings → Apps → Permissions → Background autostart**, and under the app's battery settings for **No restrictions**. Menu names vary by build. [Android service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use) · [Xiaomi autostart](https://www.mi.com/uk/support/faq/details/KA-507608/) · [Xiaomi battery settings](https://www.mi.com/global/support/faq/details/KA-538010/)

The helper is an official Shizuku **UserService** bound by the foreground service. The app watches the Shizuku binder and ends the session on loss. Closing the Shizuku manager window leaves its server running. Stopping the Shizuku server or stopping the helper ends remapping. In Shizuku 13.6, the official UserService starter registers a server-binder death callback that exits the helper process. This API therefore requires the server to remain running for an active session. [Official starter implementation](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/starter/src/main/java/moe/shizuku/starter/ServiceStarter.java#L138-L141) If the remapper stayed in the foreground while you restarted Shizuku, go Home and reopen it to receive the new binder. After a reboot, start Shizuku again and resume remapping from the app. A reminder can appear if remapping was enabled and notifications remain allowed. [Shizuku API guide](https://github.com/RikkaApps/Shizuku-API) · [Shizuku reboot requirements](https://shizuku.rikka.app/guide/setup/)

Android may require a notification tap or unlock for an activity action. A secure keyguard stays in control of protected content. Shell permissions vary by OEM firmware. If the shell cannot open evdev or uinput, or Android cannot confirm the mirror is suitable, the app reports the failure and leaves the physical device available.

## Input implementation

1. The native library runs in the UserService. It enumerates event nodes, checks the kernel name, physical identity and EV_KEY capability bitmap, and requires one eligible source. The observed `event5` number is incidental.
2. Passive testing reads the source directly. To suppress the target, the helper prepares a uinput mirror with matching identity, properties and supported key capabilities. Unsupported composite devices are refused. The helper checks Android's registered input devices and key layout before calling `EVIOCGRAB`.
3. During interception, the event loop filters the target's DOWN, UP and REPEAT, and forwards unrelated input through the mirror in report order. It excludes the mirror from discovery. Linux scan code 689 is never used as an Android keyCode. Diagnostics includes the selected layout when available and can report an Android keyCode if an event with that scan code actually reaches the activity.
4. Shutdown releases held mirror keys, removes the mirror and closes the physical input descriptor. Failures such as dropped input synchronization, forwarding errors, controller death and lease expiry end the session. A separate watchdog monitors the forwarding process. If the helper freezes while its guardian can still run, the guardian can terminate it and the kernel releases the grab. A key held during an unexpected process death can release early when Android removes the mirror. Full kernel and vendor routing still need testing on each physical model. [Linux input interface](https://docs.kernel.org/input/uinput.html) · [Android key layout selection](https://source.android.com/docs/core/interaction/input/key-layout-files)

## Build and test

Use JDK 21, the Android SDK, and the checked-in Gradle 9.3.1 wrapper. Install Android SDK Platform 37.0, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1. Set `ANDROID_HOME` to the SDK directory or configure the SDK in Android Studio. Accept the SDK licenses.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

The build produces `app/build/outputs/apk/debug/app-debug.apk` and includes the native library. On Windows run `gradlew.bat`. The debug certificate is for testing. Separate installations built with different debug keys cannot update each other in place.

Run the portable input-filter and routing tests with a host C++17 compiler and CMake:

```sh
cmake -S app/src/main/cpp -B build/native-tests
cmake --build build/native-tests
ctest --test-dir build/native-tests --output-on-failure
```

Use the [Android emulator probe](docs/emulator-testing.md) to exercise actual kernel grab, filtering and release with a disposable virtual keyboard. Follow the [real-device test procedure](docs/device-testing.md) to verify suppression, lock-screen actions, helper failures and the shared power and volume keys. The [verification record](docs/verification.md) covers the stock Android 17 camera, PIN-locked secure camera, fail-safe and reboot tests. The [license and attributions](THIRD_PARTY.md) identify upstream tools and remapper references.
