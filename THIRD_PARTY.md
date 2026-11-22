# License and third-party material

Xiaomi AI Button Remapper source is licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE). The same repository contains the corresponding Kotlin source, wrapper and build instructions for its Actions artifacts.

| Component | Use and terms |
| --- | --- |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Compose, Material 3, Activity, DataStore and coroutine runtime. Apache License 2.0. [License text](third-party/Apache-2.0.txt). |
| [Shizuku API and provider](https://github.com/RikkaApps/Shizuku-API) | Starts the raw input helper with the user's authorization. MIT, copyright RikkaW. [Full notice](third-party/Shizuku-API-LICENSE.txt). |
| [LLVM libc++](https://libcxx.llvm.org/) | Android NDK C++ runtime for the raw input helper. Apache License 2.0 with LLVM exceptions. [Notices](third-party/LLVM-LICENSE.txt). |
| [Google Material Icons](https://github.com/google/material-design-icons) | Selected Material Icons Round paths distributed as Android vector drawables. Copyright Google LLC. Apache License 2.0. [License text](third-party/Apache-2.0.txt). |
| [Gradle](https://github.com/gradle/gradle/tree/v9.3.1) | Official wrapper scripts and JAR from Gradle 9.3.1. Apache License 2.0. Wrapper scripts retain their copyright headers. |
| [JUnit 4](https://github.com/junit-team/junit4), [JSON-java](https://github.com/stleary/JSON-java) | JVM tests only. JUnit uses EPL 1.0. JSON-java licensing is available in the linked upstream project. |

## Implementation references

Button Mapper and Key Mapper document app and shortcut actions, multi-press and hold gestures, timing and vibration. Those settings informed this app's action picker, gesture controls and backups. The interface and launcher icon are original. [Button Mapper](https://buttonmapper.app/) · [Key Mapper user guide](https://keymapper.app/user-guide/keymaps/)

Key Mapper's Expert mode informed the raw input approach. This project's bounded input helper uses Linux evdev and uinput APIs with a Shizuku UserService. The picker uses Android PackageManager activity icons and LauncherApps shortcut icons. Installed apps supply their artwork at runtime. [Key Mapper Expert mode](https://keymapper.app/user-guide/expert-mode/) · [Application icons](https://github.com/keymapperorg/KeyMapper/blob/8073a40dd548c29f70105022224a6ed1db4a18ae/system/src/main/java/io/github/sds100/keymapper/system/apps/AndroidPackageManagerAdapter.kt#L416-L427) · [Shortcut provider icons](https://github.com/keymapperorg/KeyMapper/blob/8073a40dd548c29f70105022224a6ed1db4a18ae/system/src/main/java/io/github/sds100/keymapper/system/apps/AndroidAppShortcutAdapter.kt#L124-L134)

The [Nothing Button Remapper v2.2 README](https://github.com/THE-TECH-CHANGER/NothingButtonRemapper/tree/70b120accadb8f2056e7d52146a5676ba694db2c) and [Key Mapper's constraint documentation](https://keymapper.app/user-guide/constraints/) informed the profile and condition options. These settings and their Compose screens were implemented for this project.

Xiaomi, HyperOS and Android are names of their respective owners. This is an independent community project.
