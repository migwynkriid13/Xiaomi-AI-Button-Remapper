# Xiaomi AI Button Remapper

Give the physical AI button on a Xiaomi 18 Pro a camera shortcut, an app launcher, or a different action for each gesture. The initial mapping opens the camera on a single press. Remapping starts switched off.

The hardware target is the Xiaomi 18 Pro's physical `gpio-keys` button, Linux scan code `KEY_MACRO_RECORD_STOP` (689, hex `0x2B1`). The phone owner confirmed the previous release could listen to the button. Confirming HyperOS delivers it to an ordinary accessibility service, and checking whether the Xiaomi action is suppressed on a particular firmware, are separate handset tests.

<img src="docs/screenshots/button-light.png" alt="The button overview with single press assigned to Open camera" width="230"> <img src="docs/screenshots/picker-dark.png" alt="The action picker in dark mode" width="230">

## Install

[Download the latest signed APK](https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases/latest/download/Xiaomi-AI-Button-Remapper.apk) · [All releases](https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases)

Supports ARM64 Android 11 and later, targets Android 17, and includes x86-64 for emulator testing. **Upgrading from the 0.1.0 debug preview?** First use **Settings → Export configuration** to save your mappings. Uninstall that preview, install the release, then import the file. The preview used a different signing key. Later official releases use the same release certificate and can update in place.

## Use accessibility on the phone

1. Open **Diagnostics** and use the accessibility settings button. Select **Xiaomi AI Button Remapper** in Android's installed accessibility services and enable it. On some sideloaded installations Android first asks you to allow restricted settings in the app's system information screen. [Android instructions](https://support.google.com/android/answer/12623953)
2. Return to Diagnostics, allow notifications for the Stop action, and tap **Listen without remapping**. Press and release the AI button with the screen on. Look for **DOWN** and **UP**. The tester passes the button through, so Xiaomi's action can open during this check. Return to the remapper to read the events.
3. Stop the tester. Leave **Single press** set to **Open camera**, switch on **Remap button**, go Home and press once. Check that the camera opens once, Xiaomi's action stays closed, and volume and power behave as usual. Turn remapping off to restore stock input.
4. If the tester stays empty, temporarily pause other button remappers and try again. If HyperOS sends a device-specific Android keyCode with its scan code missing, use **Learn alternate key** and follow the two-press confirmation. A connected accessibility service can filter only the key events HyperOS delivers to it. Firmware can reserve a key, discard unmapped Linux events, or handle a vendor action before the accessible stream. Linux scan code 689 and Android's framework keyCode are separate fields. [Android key filtering](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onKeyEvent(android.view.KeyEvent))

This setup is completed on the phone. USB debugging, ADB, Shizuku and root are unnecessary for accessibility mode. The service filters hardware key events. A configured touch action can perform gestures. On supported Android versions an editor action can use the focused input connection. The primary button service does not retrieve the window tree.

### Optional expert input

For firmware that withholds the button from accessibility, the **Shizuku** input mode retains the previous low-level route. Install Shizuku, start its server and authorize the remapper in Diagnostics. Expand **Diagnostics → Shizuku Expert fallback**, choose **Test Expert input** for a passive check, then test the camera mapping and all shared side buttons. An eight-second AI hold releases an active expert grab. [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/)

## Actions and timing

Tap any of the four gestures to browse the searchable action catalog. Search can also find launcher apps, shortcut providers and available Android Settings screens. App and shortcut artwork appears both in the picker and on an assigned mapping when Android supplies it.

- **Media and volume.** Choose global playback controls or target one app. Pause, resume, play/pause, previous, next, rewind, fast-forward, stop and timed seek have per-player variants. Targeted commands need notification access and an active media session. Adjust individual volume streams, show the volume panel, set ringer and DND modes, mute the microphone, or choose a sound file or system ringtone.
- **Display, flashlight and navigation.** Assign brightness, rotation, portrait/landscape, flashlight strength, screenshot, Home, Back, Recents, last app, device lock, notifications and quick settings. Variable brightness needs compatible flash hardware. Android's special **Modify system settings** access permits changing display settings.
- **Apps, input and automation.** Choose an installed app, shortcut, Settings page, system assistant, activity/service/broadcast intent, URL, HTTP request, phone/SMS action, keyboard action, touch or TalkBack gesture. Build a sequence of up to 12 actions per trigger, change their order, add delays and repeat it up to five times. Each step retains its own parameters.
- **Access is per action.** The extra UI interaction service can look up an explicitly selected on-screen control and toggle the shade. Secure lock requests Android's lock-only device administrator grant. Certain radio changes open Android's confirmation panel in accessibility mode. Immediate privileged operations such as force-stopping another package require an explicit expert session. Cycling the keyboard language through Key Mapper and triggering a Key Mapper map use its external-trigger API and require a configured mapping UID. [Key Mapper action reference](https://keymapper.app/user-guide/actions/) · [External mapping setup](https://keymapper.app/user-guide/api/)

| Control | Behavior |
| --- | --- |
| Single, double, long, triple | Each gesture can have its own action. With just the default mapping, single press fires on release without a double-tap delay. Assign a multi-press action to enable its recognition window. |
| Camera and flashlight | Camera uses the system camera intent. The locked path requests the secure camera. Torch availability depends on the camera hardware and its current use. |
| Applications | Choose a launchable application by name, package and its installed icon. |
| Shortcuts | Legacy shortcut creators display their activity icons. Android supplies individual badged icons for published shortcuts when this app has shortcut-host access. A legacy provider can choose the final shortcut's artwork only after you select an item in its own UI. |
| System settings | Choose a Settings destination, such as Wi-Fi or Display. The picker shows destinations that have a handler on this Android user profile. |
| Silent and Do Not Disturb | Grant notification policy access in Settings where required. On newer Android versions, DND changes operate through the app's rule and other active rules can keep DND on. |
| URL and custom intent | Configure a web URL or a supported Android activity intent URI. External targets retain Android's normal permissions, keyguard and background launch rules. |
| Permitted shell command | In Shizuku mode, pick a specific allowed command, including media controls and device information. The helper validates the exact command and executes an argument array. |
| Disable or restore | For a disabled button, set all four actions to **No action** and turn remapping on. For the native Xiaomi behavior, turn remapping off or choose **Restore Xiaomi default**. Restoring releases the entire device and retains your mappings. |

**Settings** includes the multi-press interval, long-press threshold, debounce, haptic feedback and configuration backup. Export writes a versioned JSON file to a location you select. An import previews the actions and leaves remapping off until you enable it. Exported URLs, app choices and intent data are readable in that file. Settings live in app-private DataStore. The app has no analytics or telemetry. Its HTTP request action sends only the requests you configure. Cleartext HTTP exposes request data on the network. Android 17 can require Local network access for LAN endpoints. Exported configurations can include URLs, headers, message text and phone numbers.

## Background operation and recovery

Android binds the accessibility service independently of the settings activity. You can close the activity after enabling a mapping. Android and HyperOS control service availability after a force-stop, reboot or battery-management eviction. Check the service status and resume from the app if necessary. Active sessions use a **specialUse** foreground service and display a notification with a Stop control. Expert mode also maintains a separate shell helper. On HyperOS, allow notifications for the app. If HyperOS terminates a session, look under **Settings → Apps → Permissions → Background autostart**, and under the app's battery settings for **No restrictions**. Menu names vary by build. [Android service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use) · [Xiaomi autostart](https://www.mi.com/uk/support/faq/details/KA-507608/) · [Xiaomi battery settings](https://www.mi.com/global/support/faq/details/KA-538010/)

The expert helper is an official Shizuku **UserService** bound by the foreground service. The app watches the Shizuku binder and ends the session on loss. Closing the Shizuku manager window leaves its server running. Stopping the Shizuku server or stopping the helper ends remapping. In Shizuku 13.6, the official UserService starter registers a server-binder death callback that exits the helper process. This API therefore requires the server to remain running for an active session. [Official starter implementation](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/starter/src/main/java/moe/shizuku/starter/ServiceStarter.java#L138-L141) If the remapper stayed in the foreground while you restarted Shizuku, go Home and reopen it to receive the new binder. After a reboot, start Shizuku again and resume remapping from the app. A reminder can appear if remapping was enabled and notifications remain allowed. [Shizuku API guide](https://github.com/RikkaApps/Shizuku-API) · [Shizuku reboot requirements](https://shizuku.rikka.app/guide/setup/)

Android may require a notification tap or unlock for an activity action. A secure keyguard stays in control of protected content. Shell permissions vary by OEM firmware. If the shell cannot open evdev or uinput, or Android cannot confirm the mirror is suitable, the app reports the failure and leaves the physical device available.

## Input implementation

Accessibility mode uses Android's key-filter callback to decide whether to consume each button event. The native code below is used only in expert mode.

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

GitHub Actions publishes a signed release APK when a push to `main` changes the release source, resources, bundled notices, Gradle inputs or signing step. A README, screenshot or guide edit skips the build and release. Tests, debug sources and CI-only edits can run verification without cutting a release. Pull requests and manual runs verify the build. The release tag is `v0.2.<workflow run number>`, so version numbers can have gaps. The expected signing certificate SHA-256 is checked in under [gradle/release-certificate.sha256](gradle/release-certificate.sha256).

The build produces `app/build/outputs/apk/debug/app-debug.apk` and includes the native library. On Windows run `gradlew.bat`. The debug certificate is for testing. Separate installations built with different debug keys cannot update each other in place.

Run the portable input-filter and routing tests with a host C++17 compiler and CMake:

```sh
cmake -S app/src/main/cpp -B build/native-tests
cmake --build build/native-tests
ctest --test-dir build/native-tests --output-on-failure
```

Use the [Android emulator probe](docs/emulator-testing.md) to exercise actual kernel grab, filtering and release with a disposable virtual keyboard. Follow the [real-device test procedure](docs/device-testing.md) to verify suppression, lock-screen actions, helper failures and the shared power and volume keys. The [verification record](docs/verification.md) covers the stock Android 17 camera, PIN-locked secure camera, fail-safe and reboot tests. The [license and attributions](THIRD_PARTY.md) identify upstream tools and remapper references.
