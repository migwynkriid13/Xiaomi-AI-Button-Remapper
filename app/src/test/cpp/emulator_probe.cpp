/*
 * Standalone emulator test. Compile the backend directly into this executable
 * so the test exercises the private production grab/mirror functions without
 * adding an arbitrary-input-path API to the Android application.
 */
#include "native_input.cpp"

#include <sys/system_properties.h>
#include <iostream>

namespace {

constexpr char kFixtureName[] = "AI Remapper Emulator Fixture";
constexpr int kFixtureScan = 1001, kUnrelatedScan = 1002;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

bool emulatorProperty(const char* key) {
    char value[PROP_VALUE_MAX]{};
    return __system_property_get(key, value) > 0 && std::strcmp(value, "1") == 0;
}

void emit(int fd, uint16_t type, uint16_t code, int value) {
    input_event event{};
    event.type = type;
    event.code = code;
    event.value = value;
    if (write(fd, &event, sizeof(event)) != static_cast<ssize_t>(sizeof(event))) fail("Write test input");
}

class Fixture {
public:
    Fixture() : location("ai-remapper.avd." + std::to_string(getpid()) + "." + std::to_string(nowMs())),
                writer(open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC)) {
        if (writer.get() < 0) fail("Open /dev/uinput as emulator shell");
        uinput_setup setup{};
        setup.id.bustype = BUS_HOST;
        setup.id.vendor = 0x7ffe;
        setup.id.product = 0x1000;
        std::strcpy(setup.name, kFixtureName);
        if (ioctl(writer.get(), UI_SET_EVBIT, EV_KEY) < 0 ||
            ioctl(writer.get(), UI_SET_KEYBIT, KEY_F23) < 0 ||
            ioctl(writer.get(), UI_SET_KEYBIT, kAiKey) < 0 ||
            ioctl(writer.get(), UI_SET_EVBIT, EV_REP) < 0 ||
            ioctl(writer.get(), UI_SET_EVBIT, EV_MSC) < 0 ||
            ioctl(writer.get(), UI_SET_MSCBIT, MSC_SCAN) < 0 ||
            ioctl(writer.get(), UI_SET_PHYS, location.c_str()) < 0 ||
            ioctl(writer.get(), UI_DEV_SETUP, &setup) < 0 ||
            ioctl(writer.get(), UI_DEV_CREATE) < 0) fail("Register synthetic keyboard");

        // Ask this owned uinput fd which input device the kernel created.
        // Discover only children of that sysfs device. Never scan real keyboards.
        char sysName[128]{};
        if (ioctl(writer.get(), UI_GET_SYSNAME(sizeof(sysName)-1), sysName) < 0) fail("Get fixture sysname");
        const std::string sysDevice(sysName);
        require(sysDevice.rfind("input", 0) == 0 && sysDevice.size() > 5 &&
                sysDevice.find_first_not_of("0123456789", 5) == std::string::npos, "Invalid fixture sysname");
        const std::string directory = "/sys/class/input/" + sysDevice;
        for (int attempt = 0; attempt < 30 && source.fd.get() < 0; ++attempt) {
            DIR* dir = opendir(directory.c_str());
            if (dir) {
                while (auto* entry = readdir(dir)) {
                    const std::string node(entry->d_name);
                    if (node.rfind("event", 0) != 0 || node.size() <= 5 ||
                        node.find_first_not_of("0123456789", 5) != std::string::npos) continue;
                    const std::string path = "/dev/input/" + node;
                    Fd candidate(open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW));
                    char physical[256]{}, name[256]{};
                    if (candidate.get() >= 0 &&
                        ioctl(candidate.get(), EVIOCGPHYS(sizeof(physical)-1), physical) > 0 && location == physical &&
                        ioctl(candidate.get(), EVIOCGNAME(sizeof(name)-1), name) > 0 && std::strcmp(name, kFixtureName) == 0) {
                        source = {path, std::move(candidate)};
                        break;
                    }
                }
                closedir(dir);
            }
            if (source.fd.get() < 0) std::this_thread::sleep_for(100ms);
        }
        require(source.fd.get() >= 0, "Could not open this fixture's own evdev node");
    }

    void pressAndRelease(bool includeAi) const {
        for (int value : {1, 2, 0}) {
            if (!includeAi && value == 2) continue;
            if (includeAi) {
                emit(writer.get(), EV_MSC, MSC_SCAN, kFixtureScan);
                emit(writer.get(), EV_KEY, kAiKey, value);
                emit(writer.get(), EV_MSC, MSC_SCAN, kUnrelatedScan);
            }
            emit(writer.get(), EV_KEY, KEY_F23, value);
            emit(writer.get(), EV_SYN, SYN_REPORT, 0);
        }
    }

    Source source{"", Fd{}};
private:
    std::string location;
    Fd writer;
};

std::vector<input_event> readReports(int fd, int reports) {
    std::vector<input_event> result;
    const auto deadline = nowMs() + 1500;
    while (reports && nowMs() < deadline) {
        pollfd ready{fd, POLLIN, 0};
        if (poll(&ready, 1, 80) <= 0) continue;
        input_event e{};
        if (read(fd, &e, sizeof(e)) != static_cast<ssize_t>(sizeof(e))) fail("Read test report");
        result.push_back(e);
        if (e.type == EV_SYN && e.code == SYN_REPORT) --reports;
    }
    require(!reports, "Timed out waiting for expected reports");
    return result;
}

void verifyUnrelated(const std::vector<input_event>& events, bool expectScan) {
    std::vector<std::array<int, 3>> actual, expected;
    for (const auto& e : events) actual.push_back({e.type, e.code, e.value});
    for (int value : {1, 2, 0}) {
        if (!expectScan && value == 2) continue;
        if (expectScan) expected.push_back({EV_MSC, MSC_SCAN, kUnrelatedScan});
        expected.push_back({EV_KEY, KEY_F23, value});
        expected.push_back({EV_SYN, SYN_REPORT, 0});
    }
    require(actual == expected, "Unrelated key, scan, SYN or event ordering differs");
}

void verify() {
    require(getuid() == 2000, "Run this test as unrooted adb shell");
    require(emulatorProperty("ro.kernel.qemu") || emulatorProperty("ro.boot.qemu"),
            "This test requires an Android emulator");

    Watchdog watchdog;
    Fixture fixture;
    const Source& source = fixture.source;
    Fd originalObserver(open(source.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW));
    if (originalObserver.get() < 0) fail("Open fixture observer");

    Mirror mirror(source.fd.get());
    Route route;
    const auto deadline = nowMs() + 6000;
    do {
        route = compareRoutes(inputDump(), source.path, kMarker);
        if (!route.mirrorPath.empty()) break;
        std::this_thread::sleep_for(150ms);
    } while (nowMs() < deadline);
    require(!route.mirrorPath.empty(), "Android did not confirm a matching fixture route");
    Fd virtualObserver(open(route.mirrorPath.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW));
    if (virtualObserver.get() < 0) fail("Open mirror observer");
    std::array<unsigned int, 2> repeat{};
    require(ioctl(virtualObserver.get(), EVIOCGREP, repeat.data()) == 0 &&
            (repeat[0] == 0 || repeat[1] == 0), "Mirror can generate duplicate key repeats");
    std::cout << "Android registered " << source.path << " and " << route.mirrorPath
              << " with " << route.layout << std::endl;

    lastRenewal = nowMs();
    {
        Grab grab(source.fd.get(), watchdog);
        fixture.pressAndRelease(true);
        const auto raw = readReports(source.fd.get(), 3);
        pollfd pending{originalObserver.get(), POLLIN, 0};
        require(poll(&pending, 1, 0) == 0, "Unrelated client saw physical events during grab");
        Frame<input_event> frame;
        std::vector<int> target;
        for (const auto& e : raw) {
            const auto result = frame.append(e);
            require(result != FrameResult::LostSync, "Frame lost synchronization");
            if (result == FrameResult::Ready)
                require(frame.deliver(true, [&](const auto& event) { return mirror.send(event); },
                    [&](const auto& event) { target.push_back(event.value); return true; }), "Forwarding failed");
        }
        require(target == std::vector<int>{1, 2, 0}, "Incorrect target DOWN/REPEAT/UP");
        verifyUnrelated(readReports(virtualObserver.get(), 3), true);
    }
    fixture.pressAndRelease(false);
    verifyUnrelated(readReports(originalObserver.get(), 2), false);
}

} // namespace

int main() {
    try {
        verify();
        std::cout << "PASS: synthetic grab, 689 suppression, unrelated scan/F23/SYN, and restored source.\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "Emulator probe failed: " << e.what() << '\n';
        return 1;
    }
}
