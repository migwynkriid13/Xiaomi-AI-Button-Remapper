# Xiaomi 18 Pro device acceptance

1. Install the signed release, stop any other remapper's input grab, and start Shizuku on the phone. Leave the remapper's optional accessibility service off for the button tests.
2. Open the Button page and tap **Grant Shizuku access**. Check that the setup card disappears and the remap switch becomes available. With the screen on, tap and release the AI button during the automatic read-only test. Record DOWN, UP and whether Xiaomi's normal action runs. Tap **Test button** to repeat.
3. Assign **Open camera** to a single press and turn on **Remap button**. Use the checklist below. Turn on **Settings → Enable diagnostics** if you need detailed input and helper status.

| Check | Expected |
| --- | --- |
| Single press in the background | Close the remapper UI. One press opens Camera and Xiaomi's action stays closed with **Also run Xiaomi's action** turned off. |
| Open application | Map **Open application** to an installed app and press with a different app in front. Repeat with another app. |
| Screenshot | Choose Button actions → Take screenshot. Press the mapped gesture in a normal unlocked app. Verify Android saved an image. |
| Double, triple and long | Each runs its assigned action once. Holding the key does not create extra taps. |
| Profiles and conditions | Enable the optional accessibility service for foreground app profiles. Try one mapping in and out of the selected app. Try an unlocked or charging condition with both states. |
| Keyguard | Test an awake locked screen and a sleeping screen separately. Camera uses Android's secure camera when available. |
| Power and volume | Verify wake, lock, power menu, and both volume keys, including a press held over the AI gesture. |
| Notifications off | Try starting remapping with app notifications off. Try turning them off while remapping is active. Camera should still launch in both cases. |
| Disable | Turn remapping off in the app or hold the AI button for eight seconds. If notifications are enabled, also test **Stop now**. The next independent AI press should run the Xiaomi behavior. |
| Stop the helper | Stop the remapper from Android's active apps screen or App info. Confirm that the original action and other keys return. |
| Stop Shizuku | Stop Shizuku during active remapping. Check that status disconnects and the original action returns. Restart Shizuku and tap **Resume remapping**. |
| Reboot | Confirm the button restores its original behavior. Start Shizuku, open the remapper and resume. Saved gestures should still be present. |

If the tester never sees the button, record the phone model, HyperOS build and exact message in Diagnostics. If both actions open during active remapping, record the Xiaomi action and whether **Also run Xiaomi's action** is on.

The following developer commands are optional. Use the in-app tester for normal setup and troubleshooting.

```sh
adb shell getprop ro.build.version.incremental
adb shell getevent -pl
adb shell getevent -lt
```

Tap the AI button while the last command is running. `EV_KEY KEY_MACRO_RECORD_STOP` means Linux code 689. Its Android framework keyCode may be different. Stop with Ctrl+C after the tap.
