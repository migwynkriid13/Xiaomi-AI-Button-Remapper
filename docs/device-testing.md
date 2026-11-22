# Xiaomi 18 Pro device acceptance

1. Install the signed release. From the app's Button page, open **App info → ⋮ → Allow restricted settings**, then **Accessibility → Xiaomi AI Button Remapper**. If the App info option is absent, first open the service in Accessibility and return after the restriction message.
2. Back in the app, check that setup is hidden, the switches are available, and only Button and Settings are shown. Turn on the Diagnostics tab using **Settings → Enable diagnostics**.
3. In **Diagnostics**, start **Listen without remapping** before enabling remapping. Tap the AI key with the screen on. Record DOWN, UP, the shown framework key code and whether Xiaomi's normal action runs. If the AI key is silent, press volume once to check whether Android delivers other hardware key events, and look for a possible alternate match.
4. Stop the tester, assign Camera to a single press, and turn remapping on. Use the checklist below.

| Check | Expected |
| --- | --- |
| Single press in the background | Close the remapper UI. One press opens Camera if the phone supplies the button event, and Xiaomi's action stays closed if its routing honors the accessibility filter. |
| Screenshot | Choose Button actions → Take screenshot. Press the mapped gesture in a normal unlocked app. Verify Android saved an image. |
| Double, triple and long | Each runs its assigned action once. Holding the key does not create extra taps. |
| Profiles and conditions | Try one app-specific mapping in and out of the selected app. Try an unlocked or charging condition with both states. |
| Keyguard | Test an awake locked screen and a sleeping screen separately. Camera uses Android's secure camera when available. |
| Power and volume | Verify wake, lock, power menu, and both volume keys, including a press held over the AI gesture. |
| Disable | Turn remapping off. The next independent AI press should run the Xiaomi behavior. |
| Reboot | After unlock, check whether Android reconnects the accessibility service. Saved actions should still be present. |

If the accessibility tester never sees this button, record the phone model, HyperOS build, and whether it sees volume. If both actions open, record the Xiaomi action and the Android scan/key code in Diagnostics. Either report helps distinguish event delivery from vendor handling.

For development diagnostics only, ADB tools can compare the raw Linux event with what Android delivered. These commands play no role in installation or remapping.

```sh
adb shell getprop ro.build.version.incremental
adb shell getevent -pl
adb shell getevent -lt
```

Tap the AI button while the last command is running. `EV_KEY KEY_MACRO_RECORD_STOP` means Linux code 689. Its Android framework keyCode may be different. Stop with Ctrl+C after the tap.
