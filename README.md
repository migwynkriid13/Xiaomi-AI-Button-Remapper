<p align="center">
  <img src="app/src/main/res/drawable-nodpi/button_hero.webp" width="100%" alt="Close-up of a phone's physical side button">
</p>

<h1 align="center">Xiaomi AI Button Remapper</h1>

<p align="center">
  Customize the back screen on Xiaomi 17 and 18 Pro phones and remap the physical AI button on Xiaomi 18 Pro phones.
  <br>
  Powered by Shizuku. No root required.
</p>

<p align="center">
  <a href="https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases/latest">
    <img src="https://img.shields.io/github/v/release/migwynkriid13/Xiaomi-AI-Button-Remapper?label=release&amp;color=9A3F29" alt="Latest release">
  </a>
  <a href="https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases">
    <img src="https://img.shields.io/github/downloads/migwynkriid13/Xiaomi-AI-Button-Remapper/total?label=total%20downloads&amp;color=9A3F29" alt="Total GitHub release downloads">
  </a>
  <a href="https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/actions/workflows/android.yml">
    <img src="https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/actions/workflows/android.yml/badge.svg?branch=main" alt="APK build and verification status">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-GPLv3-4F3A31" alt="GPLv3 license">
  </a>
</p>

<p align="center">
  <strong><a href="https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases/latest/download/Xiaomi-AI-Button-Remapper.apk">Download signed APK</a></strong>
  · <a href="#get-started">Get started</a>
  · <a href="https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases">Release notes</a>
</p>

## Inside the app

<p align="center">
  <a href="docs/screenshots/button-light.png"><img src="docs/screenshots/button-light.png" width="31%" alt="Button dashboard in light mode showing all four gesture mappings"></a>
  <a href="docs/screenshots/picker-dark.png"><img src="docs/screenshots/picker-dark.png" width="31%" alt="Searchable action picker in dark mode with filters and action cards"></a>
  <a href="docs/screenshots/settings-light.png"><img src="docs/screenshots/settings-light.png" width="31%" alt="Settings in light mode showing diagnostics and gesture timing controls"></a>
</p>

## What you can do

| Phone | Back screen | AI button |
| --- | --- | --- |
| Xiaomi 17 Pro | 904 × 572 | — |
| Xiaomi 17 Pro Max | 976 × 596 | — |
| Xiaomi 18 Pro | 912 × 596 | Remapping |
| Xiaomi 18 Pro Max | 976 × 596 | Remapping |

### AI button

Assign different actions to a **single press, double press, triple press, or long press**. Open Camera with one press, skip a track with two, or hold the button to turn on the flashlight.

| Feature | What you can assign |
| --- | --- |
| **Apps and shortcuts** | Open an [installed app](docs/screenshots/apps-light.png), a [supported app shortcut](docs/screenshots/shortcuts-light.png), a Settings page, or a link. |
| **Device controls** | Take a screenshot, lock the screen, go Back or Home, or open Quick Settings. |
| **Media and sound** | Play or pause, skip or seek, adjust volume, or change the ringer or Do Not Disturb mode. |
| **App profiles and conditions** | Give a gesture a different action in a specific app, or run it only when a condition is met, such as headphones being connected. |
| **Action sequences** | Run up to 12 steps from one gesture, with optional delays and repeats. |

You can tune gesture timing, export and import your settings, and choose whether Xiaomi's original action runs alongside your mapping.

### Back screen

- **Wallpaper** supports still images, looping GIFs, galleries from selected photos or a folder, timed or shuffled rotation, four scaling modes, panning, and a draggable clock with five styles. Set how long the display stays lit, wake it on pickup, schedule wallpaper hours, or toggle it with a Quick Settings tile or home screen widget.
- **Notifications** can display the app, sender, or message and show recent alerts with a swipe. Previews follow lock-screen privacy, work profile restrictions, and Do Not Disturb.
- **Battery** animates wired and wireless charging with Ring, Edge glow, or Minimal styles. Use an automatic or custom color and choose when the animation wakes the display.
- **Mirror** uses the Quick Switch tile to move the current app between displays. Adjust the rear size, orientation, and timeout, and choose whether to return apps on lock or when the back is covered.
- **Camera** opens Xiaomi Camera on the rear display from the Camera section or with a left swipe on the wallpaper. The rear display returns to its previous state when Camera closes.

The rear display work is adapted from [Mi Back Screen](https://github.com/totallynotkasai/mi-back-screen) with the display detection changes from [PR #3](https://github.com/totallynotkasai/mi-back-screen/pull/3).

## Get started

1. Install the [latest signed APK](https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases/latest/download/Xiaomi-AI-Button-Remapper.apk).
2. Install Shizuku and start it through wireless debugging. [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging)
3. On any supported model, open **Back screen → Wallpaper**, grant Shizuku access if prompted, pick your images, and enable **Show on back screen**. Use **Setup help** in the Back screen menu for alarms, battery settings, and optional notification access.
4. On Xiaomi 18 Pro or 18 Pro Max, open **Button** and grant access if prompted. Press and release the AI button when the button test starts. **DOWN** and **UP** confirm the app can read it. You can also tap **Test button**. Choose a gesture action and turn on **Remap button**. [Setup screen](docs/screenshots/setup-light.png)

Keep Shizuku running while remapping. After restarting your phone, start Shizuku again and tap **Resume remapping**. HyperOS determines whether the AI Button sends presses while the phone is locked or the screen is off.

The back screen waits for Shizuku after reboot. Before uninstalling, return any app that is on the back screen and set **Stays lit for** to **As set** to restore Xiaomi's display settings.

On a fresh launch, the app checks GitHub Releases. When a newer version is available, tap **Download APK** to open the signed update or **Later** to keep using your current version.

## How it works

Shizuku gives the app access to the physical button. The remapper recognizes your gesture and runs the action you assigned while passing power and volume events through to Android. Turn off **Remap button** or hold the AI Button for eight seconds to restore Xiaomi's normal behavior.

App profiles and configured screen interactions use optional Accessibility access. Per-app media controls require notification access. The action picker shows which actions need extra permissions. The remapper's own notifications are optional.

The app collects no analytics or telemetry. Settings stay on your device. A configured HTTP action sends its request when you trigger the assigned gesture.

## Project links

- **Troubleshooting.** Open **Settings → Enable diagnostics** to see button detection and session status. If you report a problem, include your phone model, HyperOS version, and button test result. [GitHub Issues](https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/issues)
- **Development.** Find the build requirements and test procedures in the project documentation. [Build workflow](.github/workflows/android.yml) · [Device testing](docs/device-testing.md) · [Emulator testing](docs/emulator-testing.md) · [Verification record](docs/verification.md)
- **License.** The project is open source under the GNU General Public License v3. [License](LICENSE) · [Third-party notices](THIRD_PARTY.md)
