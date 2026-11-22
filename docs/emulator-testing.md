# Android emulator checks

Use a disposable Android emulator to check Shizuku setup and helper lifecycle. Stock emulators do not provide the Xiaomi hardware.

1. Open a fresh install of the remapper. Confirm that it prompts for Shizuku setup, the mapping controls are dimmed, and Diagnostics is hidden.
2. Start Shizuku as shell. Grant the remapper permission in the Shizuku prompt. Confirm the setup card disappears and a read-only test starts. On a stock emulator, the helper should report that no compatible physical input device was found, then exit.
3. Turn **Enable diagnostics** on and off in Settings. Verify that the tab appears and disappears. In Diagnostics, retry the read-only test and check that the failure is visible.
4. Stop Shizuku. Confirm the remapper shows setup again and offers to open Shizuku. Leave Android Accessibility off throughout these checks.

For development, native host tests cover event filtering, non-target forwarding and release on failure. For synthetic end-to-end testing, use a separate emulator-only build whose input discovery is restricted to a known uinput fixture. Have the fixture send EV_KEY 689 and separate volume and power events. Use the signed production APK on the phone to verify HyperOS routing and suppression.
