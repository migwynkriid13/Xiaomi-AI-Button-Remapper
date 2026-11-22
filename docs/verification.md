# Verification

## Accessibility-only setup and actions

The app was installed from Downloads using Android 17's Files app. App info initially had no restricted-settings menu. Attempting to open the remapper in Accessibility produced Android's access-denied screen. Returning to App info revealed **⋮ → Allow restricted settings**, after which Android let us enable **Xiaomi AI Button Remapper** and displayed its consent prompt. Android listed one accessibility service for the app.

The Button page initially displayed setup instructions and dimmed controls. Setup disappeared and the controls became active after consent. The Diagnostics tab was initially absent and appeared after enabling it in Settings.

A separate emulator-only internal `gpio-keys` fixture supplied EV_KEY 689. With **Button actions → Take screenshot** mapped to a single press and Android Display settings in front, the button saved a new PNG in `Pictures/Screenshots`. With an app profile active for Files, the same press opened Camera. Back in Display settings, it again took the default screenshot. The remapper's own notification permission was off during both tests.

Linux scan 689 reached Accessibility on this emulator. The original Xiaomi 18 Pro preview could read the physical event directly. The handset owner reported no events in the later Accessibility release. These are separate paths, and stock Android testing cannot confirm that HyperOS forwards the key. Use the [handset test procedure](device-testing.md) to verify delivery and Xiaomi action suppression.

## v0.2.12 accessibility and the action picker

These checks used the production app code on an Android 17 x86-64 emulator with SELinux enforcing. Shizuku was uninstalled. The service was enabled through Android's accessibility consent screen. The PC tools supplied input and collected test evidence. The app ran as its ordinary Android UID.

A separate virtual input fixture named `gpio-keys` supplied Linux EV_KEY 689. Android classified it as an internal input device. A separate foreground app recorded the framework events it actually received. The same driver also supplied volume and power events. The test did not change the remapper's device matching or the emulator's key layout.

| Check | Observation |
| --- | --- |
| Read-only test | Diagnostics showed accessibility connected and both button edges. The downstream app received scan 689 with Android keyCode 0 (UNKNOWN). |
| Actual suppression | With remapping active, the downstream app's target count stayed at 17 across the mapped press. The same source's volume-up DOWN and UP still reached the app with scan 115 and Android keyCode 24. Power still put the display to sleep and woke it. |
| Camera with app closed | A single press launched the stock camera directly from the remapper UID while the independent app was in front. With a PIN and an awake locked display, Android launched its secure camera and kept the keyguard engaged. The AI key did not wake the fully sleeping emulator. |
| Stop | Disabling remapping removed its foreground service. The next down/up increased the downstream target count from 17 to 19. |
| Media | Global pause reached a playing test session. After granting Android notification access, per-app play reached the chosen session. Forward/back seeks respected the track-end and zero limits. Volume changed 5 → 6 → 5. The remapper's DND rule changed Android's interruption filter from all to priority, then back to all. |
| Multi-action mapping | In the normal editor, a double press was set to About phone → 900 ms delay → Camera, repeated twice. With the settings UI closed, the four activities launched in order. The measured delays before Camera were 938 ms and 942 ms. |
| Targeted UI action | With the optional UI interaction service separately enabled in Android Settings, a mapped long press clicked an exact visible button in the selected fixture app. A different case-sensitive text selector produced a no-match diagnostic. |
| Public package artwork | Android supplied different icons for Calendar, Camera, Clock and Contacts. The Contact, Direct dial, Direct message and Settings shortcut creator rows had distinct artwork. The dashboard displayed icons for the selected Calendar app and the provider-created Internet Settings shortcut. The test account did not have shortcut-host access to enumerate other apps' private launcher shortcuts. |

The phone owner observed that the old preview could listen to the real Xiaomi button. The emulator results establish Android accessibility filtering independently. HyperOS can route a vendor shortcut differently or reserve it before accessibility sees it. The on-phone procedure checks the Xiaomi action and hardware behavior directly. [Physical acceptance steps](device-testing.md)

## Initial preview and optional Shizuku backend

The app was built on Windows and GitHub's Linux runner using the checked-in wrapper. The APK targets API 37 and contains ARM64 and x86-64 native libraries. APK signature verification and Android's 16 KiB alignment check passed. The release page records the SHA-256 digest and originating Actions run.

| Environment | Checks performed | Result |
| --- | --- | --- |
| JVM | Eight gesture/command tests and six configuration tests | Passed. Coverage includes DOWN/UP/repeat, bounce, single/double/triple/long timing, resetting incomplete gestures, command allowlisting, malformed backups and import with remapping disabled. |
| Windows and Linux hosts | C++ input-frame and routing replay | Passed. Tests cover unrelated power and volume event sequences, scan data, synchronization loss, partial failure, held-key cleanup and rejecting mismatched Android routes. |
| Linux and Android 17 x86-64 | Deliberately freeze a helper with SIGSTOP while its guardian is runnable | The guardian terminated the frozen helper. A healthy helper disarmed normally. |
| Android 17 emulator, SELinux enforcing, shell UID 2000 | Standalone kernel integration probe, three consecutive runs | Android registered both virtual keyboards. The original reader received no events during the grab. The mirror preserved F23 DOWN/REPEAT/UP with associated scans and SYN reports, filtered 689, and returned fresh input to the original reader on release. No fixture or mirror remained. |
| Normal APK on Android 17 | Shizuku 13.6 authorization, foreground-service startup, UserService binding, JNI library load, passive listener on an emulator without the physical key | The permission UI worked. The helper loaded the native library and returned a diagnostic explaining the missing source. The helper stopped and left input available. |
| Stock Android 17 emulator | UI in light/dark mode, action and app pickers, large-font landscape | Verified with the normal APK, including scrolling the picker at 200% font size. The README screenshots are from the stock emulator. Export created a versioned JSON file with four gestures in Downloads. Import via the Android document picker displayed the four-action review and succeeded. |

## Full action pipeline with a synthetic button

For these checks a separate, unpublished emulator-only APK selected a marked virtual keyboard instead of requiring a physical Xiaomi `gpio-keys` device. It retained the application's Shizuku, forwarding, watchdog, gesture and action code. The downloadable APK retains the physical-device requirement.

- An unlocked single press opened the AOSP camera with the remapper settings in the background. The live tester recorded DOWN and UP and the action dispatcher reported the launch.
- With a PIN set and the Android keyguard showing, a synthetic single press opened `com.android.camera.SecureCameraActivity`. Android still reported `showing=true` and `inputRestricted=true` afterward. The screen-off path also requested `STILL_IMAGE_CAMERA_SECURE`.
- With three distinct Settings destinations assigned, a double press opened Display, a triple press opened Accessibility, and a long hold opened the Bluetooth/connected-device screen in stock Android Settings.
- Holding the target for eight seconds disarmed the session. Killing just the input helper left Shizuku authorized, removed the mirror and displayed a helper-termination diagnostic.
- Stopping the Shizuku server ended the helper and removed the mirror. Restarting Shizuku, leaving and reopening the remapper, and choosing Resume established a fresh session.
- Rebooting with the fixture session active stopped the helper and Shizuku. The app retained gesture mappings, stayed paused and posted its resume notification.

## Physical handset acceptance

The supplied Xiaomi 18 Pro evidence establishes the Linux event identity, previous preview listening and a successful Key Mapper Expert Mode remap. This project still needs a Xiaomi 18 Pro test of Xiaomi assistant suppression, secure camera behavior, power/volume propagation, and HyperOS idle and battery management. A stock emulator cannot exercise those vendor paths. Use the [handset acceptance matrix](device-testing.md) to record results. The [emulator checklist](emulator-testing.md) explains the current Accessibility-only setup.
