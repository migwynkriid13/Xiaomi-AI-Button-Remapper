# Verification for the first preview

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

The supplied Xiaomi 18 Pro evidence establishes the Linux event identity and a successful Key Mapper Expert Mode remap. This project still needs a Xiaomi 18 Pro test of Xiaomi assistant suppression, secure camera behavior, power/volume propagation, and HyperOS idle and battery management. A stock emulator cannot exercise those vendor paths. Use the [handset acceptance matrix](device-testing.md) to record results. The [standalone emulator probe](emulator-testing.md) can be repeated before trying a new kernel or firmware.
