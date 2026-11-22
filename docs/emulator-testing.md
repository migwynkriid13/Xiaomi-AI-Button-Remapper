# Android emulator checks

Use a disposable Android 13 or later emulator for the sideloading flow. Copy the APK to Downloads, then install it through the Files app. A shell installation can be treated differently by Android and may not exercise the confirmation path.

1. Open the remapper. Confirm that App info and Accessibility setup are shown, actions and the master switch are dimmed, and Diagnostics is absent from the bottom navigation.
2. If **Allow restricted settings** is absent in App info, open the remapper's entry in Accessibility once. The denied access message should make the option available in the App info menu. Allow restricted settings, then turn on **Xiaomi AI Button Remapper** in Accessibility and accept the consent screen.
3. Return to the app. The setup panel should disappear and the controls should respond. Switch **Enable diagnostics** on and off in the app's Settings. Confirm that the tab appears and disappears.
4. Select **Take screenshot** from the Button actions group. Close the app and use a test device that sends the dedicated scan code. Confirm that Android's screenshot is saved. Ordinary emulator keyboards usually do not expose the Xiaomi scan code.

For development, a separate synthetic internal `gpio-keys` fixture can send EV_KEY 689 and independent volume/power events on a disposable emulator image that supports it. Production Android input handling uses the standard accessibility API. Keep synthetic devices out of physical-device tests. A test of stock Android cannot establish Xiaomi's own input routing.
