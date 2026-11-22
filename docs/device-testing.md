# Xiaomi 18 Pro device acceptance test

Use an unlocked, stock HyperOS phone, a trusted PC with current Android platform-tools, and the app's debug APK. Keep a working USB ADB connection for the first active test. Record model, build, active Android user, app version and Shizuku version. Disable other remappers for this session. Keep all diagnostic dumps on your own PC; they may contain unrelated input or app names.

## Identify the source with remapping off

```sh
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
adb shell getprop ro.build.version.incremental
adb shell am get-current-user
adb shell getevent -pl
adb shell getevent -lt
```

With the final command running, tap the AI button once, wait a moment, then press and hold it. Stop with Ctrl+C. Find the event node producing `EV_KEY KEY_MACRO_RECORD_STOP DOWN` and `UP` and confirm its name in the capability listing. Older getevent builds may print numeric codes. In the numeric output, `0001 02b1 00000001` is DOWN, `0001 02b1 00000000` is UP, `0001 02b1 00000002` is REPEAT, and `0000 0000` is SYN_REPORT. Repeats depend on the driver. To see numeric values on all nodes use:

```sh
adb shell getevent -t
```

To restrict capture to the dynamically selected physical node in **PowerShell**:

```powershell
$line = adb shell getevent -lt | Select-String 'KEY_MACRO_RECORD_STOP|EV_KEY\s+02b1' | Select-Object -First 1
# Press the AI button to finish the command above.
$gpioEvent = [regex]::Match($line.Line, '/dev/input/event\d+').Value
if (-not $gpioEvent) { throw 'No AI button event observed' }
adb shell getevent -t $gpioEvent
```

Inspect Android's selected key layout and device route independently:

```sh
adb shell dumpsys input > input-before.txt
adb shell ls -l /dev/input
adb shell ls -l /dev/uinput /dev/input/uinput
```

In `input-before.txt`, find the physical device by its path, then its `KeyLayoutFile`. Run `adb shell cat` followed by that exact path. Check for a `key 689` or `key 0x2b1` entry. Some layouts omit the entry. In the live tester, an Android keyCode appears only if the focused activity receives a KeyEvent with the observed scan code. Raw evdev remapping works independently of receiving this framework event. Do not use `adb shell input keyevent 689` to simulate the physical press.

## Run the acceptance matrix

Begin with all side buttons released. Check **Diagnostics → Live button tester** with interception off before testing the master switch. During an active grab, another physical-node getevent reader will ordinarily stop receiving events. Use the app's event tester and the mirror path reported by Diagnostics. Capture `adb shell dumpsys input > input-active.txt` if needed.

| Test | Procedure and pass condition |
| --- | --- |
| Passive listener | Enable the listener, press and release once. See one DOWN and one UP with timestamps. The original action remains available. Stop the listener. |
| Camera and native suppression | Enable the initial single-to-camera mapping while unlocked. Press ten times, returning to the launcher after each one. Each press opens the camera once. The Xiaomi action never opens. |
| Power and volume | Compare wake, lock and the configured long-press power menu/assistant against the baseline. Test volume tap, held repeat and release, including a volume press overlapping the AI press. There should be no stuck key, delayed release, extra volume step or change of power behavior. Disable immediately if one occurs. |
| Multiple gestures | Assign distinct observable single, double, long and triple actions. Try just inside and outside each threshold, then repeat with triple unassigned. A long hold triggers once. Held-key repeats must never create additional taps. Test a rapid single after a long hold. |
| No action and restore | Set every gesture to No action with remapping on. The AI button should be silent and power/volume should still work. Turn remapping off. The very next independent AI press should run Xiaomi's default. |
| Other actions | Pick an app, a published shortcut, a Settings screen, an HTTPS URL, a custom activity intent and a listed shell command. Try flashlight when free and when another app owns the camera. Test DND and silent mode with and without notification policy access, and with an unrelated DND rule already active. Inspect Last action for permission or availability failures. |
| UI closed | Start remapping, go Home, swipe the settings UI from Recents, then test the camera after several minutes. Verify the ongoing session notification and its Stop action. Record HyperOS battery/autostart settings and any vendor termination. |
| Locked and unlocked | Lock by pressing power. Check wake separately. Try camera with the secure keyguard active, then try app, URL and shortcut mappings. Record whether each opens, requires unlocking, offers a notification or is denied. The secure camera must keep protected content locked. Repeat unlocked. |
| Stop the Shizuku manager UI | Close only the manager window while leaving its service running. Verify Shizuku and helper status independently, the notification and a mapped press. |
| Stop the Shizuku server | With remapping active, stop the server using its manager. Record whether the helper disappears immediately or on the lease deadline, verify stock AI/power/volume, then restart Shizuku and enable remapping. This app deliberately ends capture when it loses the server. |
| Stop the helper | With Shizuku still running, choose Emergency disable or Stop in the notification. Verify the helper is inactive, Shizuku remains available, the mirror disappears and all buttons return to stock. Repeat using an eight-second hold of the AI key. |
| Client death | With interception active, run `adb shell am force-stop io.github.xiaomibutton.remapper`. Wait eight seconds. Verify native AI and all side keys, reopen the app and check recovery. |
| Reboot | With remapping active, reboot the phone normally. Confirm stock AI and power/volume before starting Shizuku. Unlock, look for the resume reminder, start Shizuku, reopen the remapper and resume. Check persisted actions and timing. |
| Import/export | Export a mapping. Change it, import the backup, inspect the preview and confirm. Remapping must be off after import. A malformed backup must produce an error and preserve existing settings. |

**Recovery from the PC** is `adb shell am force-stop io.github.xiaomibutton.remapper`. The app's Binder death and native watchdog provide release paths. If the OS itself is unresponsive, use the phone manufacturer's normal restart procedure. After recovery, capture another `adb shell dumpsys input > input-after.txt` and check that only the original route remains. Record unexpected behavior before enabling again.

For a reproducible report include the first failing step, the diagnostics message, relevant physical and mirror device records, the exact APK commit or Actions run, and whether turning remapping off immediately restores the stock behavior. Redact personal data from logs before sharing.
