# Android 17 emulator checks

The release APK only selects a physical input device. There is also a standalone NDK test that exercises the actual evdev grab and uinput forwarding code against a virtual keyboard it creates on an Android emulator. This test requires an ADB **shell** session on a QEMU Android device. It verifies the identity of the source it just created. It is excluded from the APK and the default build.

Start an API 37 x86-64 Android Virtual Device, set `ANDROID_NDK_HOME` to NDK 28.2.13676358, and put CMake and Ninja on your PATH. From the repository root:

```sh
cmake -S app/src/main/cpp -B build/probe -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-30 \
  -DANDROID_STL=c++_static -DREMAP_BUILD_EMULATOR_PROBE=ON
cmake --build build/probe --target evdev_emulator_probe
adb devices -l
# Choose the emulator's serial from the output above.
AVD_SERIAL=emulator-5554
adb -s "$AVD_SERIAL" shell id
adb -s "$AVD_SERIAL" shell getenforce
adb -s "$AVD_SERIAL" push build/probe/evdev_emulator_probe /data/local/tmp/evdev_emulator_probe
adb -s "$AVD_SERIAL" shell chmod 755 /data/local/tmp/evdev_emulator_probe
adb -s "$AVD_SERIAL" shell timeout 30 /data/local/tmp/evdev_emulator_probe
```

The probe registers its own source, checks the mirror in Android's input service, grabs the fixture and emits two scan codes with their respective key transitions. It checks that 689 disappears from the forwarded reports, that F23 DOWN/REPEAT/UP keeps its scan and SYN data, and that fresh input returns to the original reader after releasing the grab. Exit code zero indicates success. A denied ioctl, unreadable route or unsupported emulator results in a diagnostic failure.

On the same emulator, the normal APK should still report **No readable physical gpio-keys advertising EV_KEY 689** when you authorize Shizuku and try the passive listener. That verifies the production discovery path refuses a device without the target hardware.
