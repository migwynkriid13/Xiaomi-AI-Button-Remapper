<p align="center">
  <img src="app/src/main/res/drawable-nodpi/button_hero.webp" width="100%" alt="Close-up of a phone's physical side button">
</p>

<h1 align="center">Xiaomi AI Button Remapper</h1>

<p align="center">
  Turn the physical AI Button on Xiaomi 18 Pro and Xiaomi 18 Pro Max into a shortcut for the actions you use most.
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

Assign different actions to a **single press, double press, triple press, or long press**. Open Camera with one press, skip a track with two, or hold the button to turn on the flashlight.

| Feature | What you can assign |
| --- | --- |
| **Apps and shortcuts** | Open an [installed app](docs/screenshots/apps-light.png), a [supported app shortcut](docs/screenshots/shortcuts-light.png), a Settings page, or a link. |
| **Device controls** | Take a screenshot, lock the screen, go Back or Home, or open Quick Settings. |
| **Media and sound** | Play or pause, skip or seek, adjust volume, or change the ringer or Do Not Disturb mode. |
| **App profiles and conditions** | Give a gesture a different action in a specific app, or run it only when a condition is met, such as headphones being connected. |
| **Action sequences** | Run up to 12 steps from one gesture, with optional delays and repeats. |

You can tune gesture timing, export and import your settings, and choose whether Xiaomi's original action runs alongside your mapping.

## Get started

Use a **Xiaomi 18 Pro** or **Xiaomi 18 Pro Max** with the dedicated AI Button and an up-to-date HyperOS build.

1. Install the [latest signed APK](https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases/latest/download/Xiaomi-AI-Button-Remapper.apk).
2. Install Shizuku and start it through wireless debugging. [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging)
3. Open the remapper and tap **Grant Shizuku access**. Press and release the AI Button when the button test starts. **DOWN** and **UP** confirm the app can read it. Use **Test button** to repeat the check. [Setup screen](docs/screenshots/setup-light.png)
4. Choose an action for a gesture and turn on **Remap button**.

Keep Shizuku running while remapping. After restarting your phone, start Shizuku again and tap **Resume remapping**. HyperOS determines whether the AI Button sends presses while the phone is locked or the screen is off.

On a fresh launch, the app checks GitHub Releases. When a newer version is available, tap **Download APK** to open the signed update or **Later** to keep using your current version.

## How it works

Shizuku gives the app access to the physical button. The remapper recognizes your gesture and runs the action you assigned while passing power and volume events through to Android. Turn off **Remap button** or hold the AI Button for eight seconds to restore Xiaomi's normal behavior.

App profiles and configured screen interactions use optional Accessibility access. Per-app media controls require notification access. The action picker shows which actions need extra permissions. The remapper's own notifications are optional.

The app collects no analytics or telemetry. Settings stay on your device. A configured HTTP action sends its request when you trigger the assigned gesture.

## Project links

- **Troubleshooting.** Open **Settings → Enable diagnostics** to see button detection and session status. If you report a problem, include your phone model, HyperOS version, and button test result. [GitHub Issues](https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/issues)
- **Development.** Find the build requirements and test procedures in the project documentation. [Build workflow](.github/workflows/android.yml) · [Device testing](docs/device-testing.md) · [Emulator testing](docs/emulator-testing.md) · [Verification record](docs/verification.md)
- **License.** The project is open source under the GNU General Public License v3. [License](LICENSE) · [Third-party notices](THIRD_PARTY.md)
