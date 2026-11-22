#include "android_routing.hpp"
#include "event_filter.hpp"

#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using namespace remap;

namespace {
struct Event {
    uint16_t type, code;
    int32_t value;
    int64_t timestamp;
    bool operator==(const Event& b) const {
        return type == b.type && code == b.code && value == b.value && timestamp == b.timestamp;
    }
};
constexpr Event syn{kSync, kReport, 0, 100};
constexpr Event ai{kKey, kAiKey, 1, 81};
constexpr Event power{kKey, 116, 1, 78};
constexpr Event volume{kKey, 115, 2, 88};
constexpr Event volumeUp{kKey, 115, 0, 100};
constexpr Event scanPower{kMisc, kScan, 200, 77};
constexpr Event scanAi{kMisc, kScan, 201, 80};
constexpr Event scanVolume{kMisc, kScan, 202, 87};

void check(bool valid, const char* message) {
    if (!valid) throw std::runtime_error(message);
}

void testMixedFrame() {
    Frame<Event> frame;
    std::vector<Event> forwarded, target;
    for (auto e : {scanPower, power, scanAi, ai, scanVolume, volume, volumeUp})
        check(frame.append(e) == FrameResult::Pending, "delivered incomplete frame");
    check(frame.append(syn) == FrameResult::Ready, "missed report");
    check(frame.deliver(true, [&](auto e) { forwarded.push_back(e); return true; },
                             [&](auto e) { target.push_back(e); return true; }), "failed to forward");
    check(forwarded == std::vector<Event>{scanPower, power, scanVolume, volume, volumeUp, syn},
          "unrelated event data, scan, order, repeat, timestamp or report changed");
    check(target == std::vector<Event>{ai}, "target was not isolated");
}

void testObservationAndRawValues() {
    Frame<Event> frame;
    std::vector<int> values;
    for (int v : {1, 2, 2, 0, 9}) {
        frame.append({kKey, kAiKey, v, 30});
        frame.append(power);
        check(frame.append(syn) == FrameResult::Ready, "missing SYN");
        check(frame.deliver(false, [](auto) { throw std::logic_error("observation forwarded input"); return false; },
                            [&](auto e) { values.push_back(e.value); return true; }), "observation failed");
    }
    check(values == std::vector<int>{1,2,2,0}, "target press/repeat/release values changed");
}

void testFailures() {
    Frame<Event> frame;
    frame.append(power);
    check(frame.append({kSync,kDropped,0,85}) == FrameResult::LostSync, "ignored kernel overrun");
    Frame<Event> overflow;
    for (int i = 0; i < 256; ++i) overflow.append(power);
    check(overflow.append(syn) == FrameResult::LostSync, "ignored malformed frame");
    Frame<Event> failed;
    failed.append(ai); failed.append(power); failed.append(syn);
    bool callback = false;
    check(!failed.deliver(true, [](auto) {return false;}, [&](auto) {callback=true; return true;}),
          "continued after mirror write failed");
    check(!callback, "executed a mapping from a failed frame");
    Frame<Event> lostCallback;
    lostCallback.append(ai); lostCallback.append(syn);
    check(!lostCallback.deliver(true, [](auto) {return true;}, [](auto) {return false;}),
          "did not stop after callback failure");
}

void testEmergencyAndCleanup() {
    EmergencyHold hold;
    hold.key(2, 0);
    check(!hold.expired(9000), "stray repeat armed emergency stop");
    hold.key(1, 1000); hold.key(2, 3000); hold.key(1, 5000);
    check(!hold.expired(8999) && hold.expired(9000), "incorrect hold threshold or duplicate handling");
    hold.key(0, 9001);
    check(!hold.expired(15000), "release left timer active");
    ForwardedKeys<768> keys;
    keys.written(power); keys.written(volume);
    keys.written(Event{kKey,115,1,1}); keys.written(volumeUp);
    std::vector<size_t> released;
    keys.releaseHeld([&](size_t code){ released.push_back(code); });
    check(released == std::vector<size_t>{116}, "shutdown must release exactly held forwarded keys");
}

std::string hub(int id, std::string path, std::string location, std::string layout) {
    return "    " + std::to_string(id) + ": gpio-keys\n      Classes: KEYBOARD\n      Path: " + path +
        "\n      Enabled: true\n      Location: " + location + "\n      Identifier: bus=0019\n      KeyLayoutFile: " +
        layout + "\n      KeyCharacterMapFile: /system/Generic.kcm\n      ConfigurationFile: \n";
}
std::string reader(int id, std::string external, std::string waking) {
    return "  Device " + std::to_string(id) + ": gpio-keys\n  EventHub Devices: [ " + std::to_string(id) +
        " ] \n    IsExternal: " + external + "\n    IsWaking: " + waking +
        "\n    Sources: KEYBOARD\n    KeyboardType: 1\n    AssociatedDisplayPort: <none>\n";
}
void testRoutes() {
    const std::string physical = "/dev/input/event22";
    const std::string virtualNode = "/dev/input/event30";
    const std::string layout = "/vendor/usr/keylayout/gpio-keys.kl";
    const std::string h = "Event Hub State:\n  Devices:\n" + hub(22,physical,"gpio-keys/input0",layout);
    const std::string dest = hub(30,virtualNode,"remap",layout);
    const std::string readers = "Input Reader State (Nums of device: 2):\n" + reader(22,"false","true");
    const std::string r = reader(30,"false","true");
    auto route = [&](std::string s) { return compareRoutes(s, physical, "remap").mirrorPath; };
    check(route(h + dest + readers + r) == virtualNode, "matching Android route refused");
    check(route(h + dest + readers).empty(), "EventHub registration alone accepted");
    check(route(h + dest + readers + reader(30,"true","true")).empty(), "external route accepted");
    check(route(h + dest + readers + reader(30,"false","false")).empty(), "wake mismatch accepted");
    check(route(h + hub(30,virtualNode,"remap","/system/Generic.kl") + readers + r).empty(), "wrong layout accepted");
    check(route(h + dest + hub(31,"/dev/input/event31","remap",layout) + readers + r).empty(), "ambiguous mirror accepted");
    check(describeMapping("# key 689 NO\nkey usage 689 A\nkey 0x2b1 AI WAKE\n") == "AI", "layout label lookup failed");
    check(describeMapping("key 688 B\n").empty(), "invented Android mapping");
}
} // namespace

int main() {
    try {
        testMixedFrame(); testObservationAndRawValues(); testFailures(); testEmergencyAndCleanup(); testRoutes();
        std::cout << "Input frame, state, failures and Android routing passed.\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}

