#include "android_routing.hpp"
#include "event_filter.hpp"
#include "watchdog.hpp"

#include <jni.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

extern char** environ;

namespace {
using namespace remap;
using namespace std::chrono_literals;
static_assert(EV_KEY == kKey && EV_SYN == kSync && SYN_REPORT == kReport &&
              EV_MSC == kMisc && MSC_SCAN == kScan && SYN_DROPPED == kDropped);

constexpr char kMarker[] = "xiaomi-ai-remapper/pass-through";
constexpr int64_t kLeaseMs = 6000;
std::mutex session;
std::atomic<bool> stopRequested{false};
std::atomic<int64_t> lastRenewal{0};

int64_t nowMs() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return int64_t(t.tv_sec) * 1000 + t.tv_nsec / 1000000;
}

void checkLease() {
    if (stopRequested.load()) throw std::runtime_error("Stopped");
    if (nowMs() - lastRenewal.load() > kLeaseMs)
        throw std::runtime_error("Controller heartbeat expired. Remapping stopped.");
}

[[noreturn]] void fail(const std::string& operation) {
    const int error = errno;
    throw std::runtime_error(operation + ": " + std::strerror(error));
}

class Fd {
public:
    explicit Fd(int v = -1) : value(v) {}
    ~Fd() { if (value >= 0) close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    Fd(Fd&& src) noexcept : value(std::exchange(src.value, -1)) {}
    Fd& operator=(Fd&& src) noexcept {
        if (this != &src) {
            if (value >= 0) close(value);
            value = std::exchange(src.value, -1);
        }
        return *this;
    }
    int get() const { return value; }
private:
    int value;
};

template<size_t N> bool bit(const std::array<unsigned char, N>& bits, size_t i) {
    return i / 8 < N && (bits[i / 8] & (1u << (i % 8)));
}
template<int Max> auto capabilities(int fd, int type) {
    std::array<unsigned char, Max / 8 + 1> bits{};
    if (ioctl(fd, EVIOCGBIT(type, sizeof(bits)), bits.data()) < 0) fail("Read event capabilities");
    return bits;
}

struct Source {
    std::string path;
    Fd fd;
};

Source findGpio() {
    DIR* dir = opendir("/dev/input");
    if (!dir) fail("Open /dev/input in Shizuku helper");
    std::vector<std::string> events;
    while (auto entry = readdir(dir)) {
        const std::string name(entry->d_name);
        if (name.rfind("event", 0) == 0 && name.size() > 5 &&
            name.find_first_not_of("0123456789", 5) == std::string::npos) events.push_back(name);
    }
    closedir(dir);
    Source source{"", Fd{}};
    unsigned unreadable = 0;
    for (const auto& event : events) {
        const std::string path = "/dev/input/" + event;
        Fd candidate(open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW));
        if (candidate.get() < 0) { ++unreadable; continue; }
        char name[256]{}, location[256]{};
        if (ioctl(candidate.get(), EVIOCGNAME(sizeof(name)-1), name) <= 0 ||
            std::strcmp(name, "gpio-keys") != 0) continue;
        ioctl(candidate.get(), EVIOCGPHYS(sizeof(location)-1), location);
        if (std::strcmp(location, kMarker) == 0) continue;
        if (!bit(capabilities<KEY_MAX>(candidate.get(), EV_KEY), kAiKey)) continue;
        char canonical[PATH_MAX];
        const std::string sys = "/sys/class/input/" + event + "/device";
        if (realpath(sys.c_str(), canonical)) {
            if (std::strstr(canonical, "/virtual/")) continue;
        } else if (std::strcmp(location, "gpio-keys/input0") != 0) {
            continue;
        }
        if (source.fd.get() >= 0) throw std::runtime_error("Multiple physical gpio-keys devices support 689. Cannot select safely.");
        source.path = path;
        source.fd = std::move(candidate);
    }
    if (source.fd.get() < 0)
        throw std::runtime_error("No readable physical gpio-keys advertising EV_KEY 689. Unreadable input nodes: " +
                                 std::to_string(unreadable) + ".");
    int clockId = CLOCK_MONOTONIC;
    if (ioctl(source.fd.get(), EVIOCSCLOCKID, &clockId) < 0) fail("Select monotonic input timestamps");
    return source;
}

class Mirror {
public:
    explicit Mirror(int source) {
        const auto types = capabilities<EV_MAX>(source, 0);
        // Stateful switches, axes and bidirectional LEDs/FF need additional
        // synchronisation. Reject any such composite before creating or grabbing.
        for (int type = 0; type <= EV_MAX; ++type) {
            if (bit(types, type) && type != EV_SYN && type != EV_KEY && type != EV_MSC && type != EV_REP)
                throw std::runtime_error("Unsupported gpio-keys event type " + std::to_string(type) +
                                         ". Original device left active.");
        }
        fd = Fd(open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC));
        if (fd.get() < 0 && errno == ENOENT)
            fd = Fd(open("/dev/input/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC));
        if (fd.get() < 0) fail("Open uinput in Shizuku helper");
        for (int type : {EV_KEY, EV_MSC, EV_REP}) {
            if (bit(types, type) && ioctl(fd.get(), UI_SET_EVBIT, type) < 0) fail("Copy event type");
        }
        const auto keys = capabilities<KEY_MAX>(source, EV_KEY);
        for (int code = 0; code <= KEY_MAX; ++code)
            if (bit(keys, code) && ioctl(fd.get(), UI_SET_KEYBIT, code) < 0) fail("Copy key code");
        if (bit(types, EV_MSC)) {
            const auto misc = capabilities<MSC_MAX>(source, EV_MSC);
            for (int code = 0; code <= MSC_MAX; ++code)
                if (bit(misc, code) && ioctl(fd.get(), UI_SET_MSCBIT, code) < 0) fail("Copy miscellaneous code");
        }
        std::array<unsigned char, INPUT_PROP_MAX / 8 + 1> props{};
        if (ioctl(source, EVIOCGPROP(sizeof(props)), props.data()) < 0) fail("Read device properties");
        for (int prop = 0; prop <= INPUT_PROP_MAX; ++prop)
            if (bit(props, prop) && ioctl(fd.get(), UI_SET_PROPBIT, prop) < 0) fail("Copy device property");

        uinput_setup setup{};
        if (ioctl(source, EVIOCGID, &setup.id) < 0 ||
            ioctl(source, EVIOCGNAME(sizeof(setup.name)-1), setup.name) <= 0) fail("Read hardware identity");
        if (ioctl(fd.get(), UI_SET_PHYS, kMarker) < 0 || ioctl(fd.get(), UI_DEV_SETUP, &setup) < 0 ||
            ioctl(fd.get(), UI_DEV_CREATE) < 0) fail("Create uinput mirror");
        if (bit(types, EV_REP)) {
            // Repeat values already arrive from the physical fd. Prevent the
            // mirror's own timer from duplicating them, then verify via EVIOCGREP.
            input_event setting{};
            setting.type = EV_REP;
            for (int parameter : {REP_DELAY, REP_PERIOD}) {
                setting.code = static_cast<uint16_t>(parameter);
                if (!send(setting)) fail("Disable mirror-generated repeat");
            }
            if (!send(input_event{})) fail("Synchronize repeat configuration");
        }
    }

    ~Mirror() {
        // InputReader also resets device state on removal. Explicit releases
        // cover clean shutdown while a forwarded volume or power key is held.
        pressed.releaseHeld([&](size_t code) {
            input_event up{}; up.type = EV_KEY; up.code = static_cast<uint16_t>(code); send(up);
        });
        input_event sync{};
        sync.type = EV_SYN;
        sync.code = SYN_REPORT;
        send(sync);
        ioctl(fd.get(), UI_DEV_DESTROY);
    }

    bool send(const input_event& event) {
        ssize_t written;
        do { written = write(fd.get(), &event, sizeof(event)); } while (written < 0 && errno == EINTR);
        if (written != sizeof(event)) return false;
        pressed.written(event);
        return true;
    }
private:
    Fd fd;
    ForwardedKeys<KEY_MAX+1> pressed;
};

// dumpsys uses the public Android diagnostic entry point. Never run through a
// shell, persist a dump, or wait indefinitely for system_server's input service.
std::string inputDump() {
    int pipes[2];
    if (pipe2(pipes, O_CLOEXEC) < 0) fail("Open input-service diagnostic pipe");
    Fd readEnd(pipes[0]), writeEnd(pipes[1]);
    if (fcntl(readEnd.get(), F_SETFL, O_NONBLOCK) < 0) fail("Configure diagnostic pipe");
    posix_spawn_file_actions_t actions;
    if (posix_spawn_file_actions_init(&actions) != 0) throw std::runtime_error("Cannot initialise dumpsys");
    int rc = posix_spawn_file_actions_adddup2(&actions, writeEnd.get(), STDOUT_FILENO);
    if (!rc) rc = posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, "/dev/null", O_WRONLY, 0);
    char bin[] = "/system/bin/dumpsys", timeoutOption[] = "-t", timeout[] = "2", service[] = "input";
    char* argv[] = {bin, timeoutOption, timeout, service, nullptr};
    pid_t pid = -1;
    if (!rc) rc = posix_spawn(&pid, bin, &actions, nullptr, argv, environ);
    posix_spawn_file_actions_destroy(&actions);
    if (rc) throw std::runtime_error("InputReader verification failed: " + std::string(std::strerror(rc)));
    writeEnd = Fd{};
    const int64_t until = nowMs() + 2500;
    std::string result;
    std::array<char, 8192> buffer;
    bool complete = false;
    while (nowMs() < until && !stopRequested && result.size() <= 8 * 1024 * 1024) {
        pollfd pipeFd{readEnd.get(), POLLIN, 0};
        const int ready = poll(&pipeFd, 1, 100);
        if (ready <= 0) { if (ready < 0 && errno != EINTR) break; continue; }
        const ssize_t n = read(readEnd.get(), buffer.data(), buffer.size());
        if (n > 0) result.append(buffer.data(), static_cast<size_t>(n));
        if (n == 0) { complete = true; break; }
        if (n < 0 && errno != EINTR && errno != EAGAIN) break;
    }
    if (!complete) kill(pid, SIGKILL);
    int status = 0;
    pid_t waited;
    do { waited = waitpid(pid, &status, 0); } while (waited < 0 && errno == EINTR);
    return complete && waited == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0 ? result : "";
}

class Callbacks {
public:
    Callbacks(JNIEnv* env, jobject receiver) : env(env), receiver(receiver) {
        jclass cls = env->GetObjectClass(receiver);
        statusMethod = env->GetMethodID(cls, "onStatus", "(Ljava/lang/String;)V");
        if (!env->ExceptionCheck()) keyMethod = env->GetMethodID(cls, "onKey", "(IJ)V");
        env->DeleteLocalRef(cls);
        if (!statusMethod || !keyMethod || env->ExceptionCheck()) {
            env->ExceptionClear();
            throw std::runtime_error("Input callback interface is unavailable");
        }
    }
    void status(const std::string& s) const {
        jstring msg = env->NewStringUTF(s.c_str());
        if (msg) {
            env->CallVoidMethod(receiver, statusMethod, msg);
            env->DeleteLocalRef(msg);
        }
        if (env->ExceptionCheck()) { env->ExceptionClear(); throw std::runtime_error("Status receiver failed"); }
    }
    bool key(int value, int64_t time) const {
        env->CallVoidMethod(receiver, keyMethod, static_cast<jint>(value), static_cast<jlong>(time));
        if (!env->ExceptionCheck()) return true;
        env->ExceptionClear();
        return false;
    }
private:
    JNIEnv* env;
    jobject receiver;
    jmethodID statusMethod = nullptr, keyMethod = nullptr;
};

bool allReleased(int fd) {
    std::array<unsigned char, KEY_MAX / 8 + 1> state{};
    if (ioctl(fd, EVIOCGKEY(sizeof(state)), state.data()) < 0) fail("Check for held hardware keys");
    return std::all_of(state.begin(), state.end(), [](unsigned char byte) { return byte == 0; });
}

class Grab {
public:
    explicit Grab(int fd) : fd(fd) {
        if (ioctl(fd, EVIOCGRAB, 1) < 0) fail("Grab gpio-keys");
    }
    ~Grab() { ioctl(fd, EVIOCGRAB, 0); }
private:
    int fd;
};

void loop(int fd, Mirror* mirror, Watchdog* watchdog, const Callbacks& callbacks) {
    Frame<input_event> frame;
    EmergencyHold emergency;
    int64_t frameStart = 0;
    std::array<input_event, 64> events{};
    while (true) {
        checkLease();
        if (watchdog) watchdog->beat();
        if (mirror && emergency.expired(nowMs())) throw std::runtime_error("Emergency stop: AI button held eight seconds.");
        if (frame.incomplete() && nowMs() - frameStart > 1000) throw std::runtime_error("Input frame timed out.");
        pollfd p{fd, POLLIN, 0};
        const int ready = poll(&p, 1, 100);
        if (ready < 0) { if (errno == EINTR) continue; fail("Poll physical input"); }
        if (!ready) continue;
        if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) throw std::runtime_error("Physical input disconnected.");
        const ssize_t bytes = read(fd, events.data(), sizeof(events));
        if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) continue;
        if (bytes < 0) fail("Read physical input");
        if (!bytes || bytes % sizeof(input_event)) throw std::runtime_error("Incomplete physical input read.");
        for (size_t i = 0; i < static_cast<size_t>(bytes) / sizeof(input_event); ++i) {
            if (!frame.incomplete()) frameStart = nowMs();
            const auto result = frame.append(events[i]);
            if (result == FrameResult::LostSync)
                throw std::runtime_error("Input synchronization lost. Original route restored.");
            if (result == FrameResult::Ready && !frame.deliver(mirror != nullptr,
                    [&](const input_event& e) { return mirror->send(e); },
                    [&](const input_event& e) {
                        const int64_t time = int64_t(e.time.tv_sec) * 1000 + e.time.tv_usec / 1000;
                        emergency.key(e.value, time);
                        return callbacks.key(e.value, time);
                    })) throw std::runtime_error("Input forwarding or event delivery failed.");
        }
    }
}

void monitor(bool intercept, const Callbacks& callbacks) {
    std::optional<Watchdog> watchdog;
    if (intercept) watchdog.emplace();
    Source source = findGpio();
    const int fd = source.fd.get();
    if (!intercept) {
        callbacks.status("Observing: " + source.path + " (gpio-keys, EV_KEY 689)");
        loop(fd, nullptr, nullptr, callbacks);
        return;
    }
    callbacks.status("Preparing gpio-keys mirror for " + source.path);
    Mirror mirror(fd);
    Route route;
    const int64_t deadline = nowMs() + 6000;
    do {
        checkLease();
        route = compareRoutes(inputDump(), source.path, kMarker);
        if (!route.mirrorPath.empty()) break;
        std::this_thread::sleep_for(200ms);
    } while (nowMs() < deadline);
    if (route.mirrorPath.empty())
        throw std::runtime_error("Android did not confirm an enabled mirror with matching key layout and input routing. Original device left active.");

    // Record the layout selected by Android. The Linux code is never passed
    // to Android as a KeyEvent keyCode.
    std::string mapping;
    if (route.layout.size() > 3 && route.layout.substr(route.layout.size()-3) == ".kl") {
        std::ifstream layout(route.layout);
        if (layout) { std::ostringstream bytes; bytes << layout.rdbuf(); mapping = describeMapping(bytes.str()); }
    }
    const auto types = capabilities<EV_MAX>(fd, 0);
    if (bit(types, EV_REP)) {
        Fd virtualNode(open(route.mirrorPath.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW));
        std::array<unsigned int, 2> repeat{};
        if (virtualNode.get() < 0 || ioctl(virtualNode.get(), EVIOCGREP, repeat.data()) < 0 ||
            (repeat[0] && repeat[1]))
            throw std::runtime_error("Cannot verify that the mirror has kernel auto-repeat disabled.");
    }

    // Events queued during Android registration already went to the physical
    // device. Drop only that pre-grab queue, and start with every key released.
    std::array<input_event, 64> stale{};
    while (true) {
        checkLease();
        const auto n = read(fd, stale.data(), sizeof(stale));
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && errno == EAGAIN) break;
        if (n <= 0) throw std::runtime_error("Input device disappeared while preparing.");
    }
    if (!allReleased(fd)) throw std::runtime_error("Release all side buttons, then enable again.");
    watchdog->arm();
    Grab grab(fd);
    pollfd pending{fd, POLLIN, 0};
    if (!allReleased(fd) || poll(&pending, 1, 0) != 0)
        throw std::runtime_error("Input changed while arming. Release all side buttons and retry.");
    callbacks.status("Active: " + source.path + " → " + route.mirrorPath + " | " + route.layout +
                     " | 689: " + (mapping.empty() ? "no scan-code entry found" : mapping));
    loop(fd, &mirror, &*watchdog, callbacks);
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_xiaomibutton_remapper_runtime_NativeInput_run(JNIEnv* env, jobject, jboolean intercept, jobject receiver) {
    std::unique_lock<std::mutex> guard(session, std::try_to_lock);
    if (!guard.owns_lock()) return env->NewStringUTF("An input session is already running.");
    stopRequested = false;
    lastRenewal = nowMs();
    try {
        Callbacks callbacks(env, receiver);
        monitor(intercept, callbacks);
        return env->NewStringUTF("Stopped");
    } catch (const std::exception& e) {
        return env->NewStringUTF(e.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_xiaomibutton_remapper_runtime_NativeInput_stop(JNIEnv*, jobject) { stopRequested = true; }

extern "C" JNIEXPORT void JNICALL
Java_io_github_xiaomibutton_remapper_runtime_NativeInput_renew(JNIEnv*, jobject) { lastRenewal = nowMs(); }

