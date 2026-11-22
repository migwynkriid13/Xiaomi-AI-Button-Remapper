# License and third-party material

Xiaomi AI Button Remapper source is licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE). The same repository contains the corresponding Kotlin, AIDL and native source, wrapper and build instructions for its Actions artifacts.

| Component | Use and terms |
| --- | --- |
| [Shizuku API and provider](https://github.com/RikkaApps/Shizuku-API) | Runtime dependency. MIT, copyright RikkaW. [Full notice](third-party/Shizuku-API-LICENSE.txt). Users who select expert input can install the separate Shizuku manager. |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Compose, Material 3, Activity, DataStore and coroutine runtime. Apache License 2.0. [License text](third-party/Apache-2.0.txt). |
| [Google Material Icons](https://github.com/google/material-design-icons) | Selected Material Icons Round paths distributed as Android vector drawables. Copyright Google LLC. Apache License 2.0. [License text](third-party/Apache-2.0.txt). |
| [Gradle](https://github.com/gradle/gradle/tree/v9.3.1) | Official wrapper scripts and JAR from Gradle 9.3.1. Apache License 2.0. Wrapper scripts retain their copyright headers. |
| [LLVM libc++](https://libcxx.llvm.org/) | Android NDK C++ runtime, statically linked. Apache License 2.0 with LLVM exceptions. [LLVM license and notices](third-party/LLVM-LICENSE.txt). |
| [JUnit 4](https://github.com/junit-team/junit4), [JSON-java](https://github.com/stleary/JSON-java) | JVM tests only. JUnit uses EPL 1.0. JSON-java licensing is available in the linked upstream project. |

## Implementation references

Key Mapper's GPLv3 sources illustrate the architecture of an evdev grab, uinput device and event loop. This project's C++ implementation and test fixtures were written for this repository. [Grabbed device](https://github.com/keymapperorg/KeyMapper/blob/8073a40dd548c29f70105022224a6ed1db4a18ae/evdev/src/main/rust/evdev_manager/core/src/grabbed_device.rs) · [Event loop](https://github.com/keymapperorg/KeyMapper/blob/8073a40dd548c29f70105022224a6ed1db4a18ae/evdev/src/main/rust/evdev_manager/core/src/event_loop.rs) · [Grab controller](https://github.com/keymapperorg/KeyMapper/blob/8073a40dd548c29f70105022224a6ed1db4a18ae/evdev/src/main/rust/evdev_manager/core/src/evdev_grab_controller.rs)

Button Mapper and Key Mapper document app and shortcut actions, multi-press and hold gestures, timing and vibration. Those settings informed this app's action picker, gesture controls and backups. The interface and launcher icon are original. [Button Mapper](https://buttonmapper.app/) · [Key Mapper user guide](https://keymapper.app/user-guide/keymaps/)

Key Mapper's standard input service and picker were also reviewed. This app uses Android's accessibility key filter, PackageManager activity icons and LauncherApps shortcut icons. Installed apps supply their own artwork at runtime. [Key Mapper accessibility setup](https://keymapper.app/quick-start/) · [Application icons](https://github.com/keymapperorg/KeyMapper/blob/8073a40dd548c29f70105022224a6ed1db4a18ae/system/src/main/java/io/github/sds100/keymapper/system/apps/AndroidPackageManagerAdapter.kt#L416-L427) · [Shortcut provider icons](https://github.com/keymapperorg/KeyMapper/blob/8073a40dd548c29f70105022224a6ed1db4a18ae/system/src/main/java/io/github/sds100/keymapper/system/apps/AndroidAppShortcutAdapter.kt#L124-L134)

Xiaomi, HyperOS and Android are names of their respective owners. This is an independent community project.
