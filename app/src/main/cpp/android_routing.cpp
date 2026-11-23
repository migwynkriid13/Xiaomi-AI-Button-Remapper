#include "android_routing.hpp"
#include "event_filter.hpp"

#include <algorithm>
#include <charconv>
#include <cstdlib>
#include <map>
#include <sstream>
#include <vector>

namespace remap {
namespace {

using Fields = std::map<std::string, std::string>;
struct Hub { int id; Fields fields; };
struct Reader { std::vector<int> hubs; Fields fields; };

std::string trim(const std::string& str) {
    auto from = str.find_first_not_of(" \t\r");
    if (from == std::string::npos) return {};
    return str.substr(from, str.find_last_not_of(" \t\r") - from + 1);
}

int deviceId(const std::string& line) {
    auto end = line.find(':');
    if (end == std::string::npos) return -1;
    int id = -1;
    const auto parsed = std::from_chars(line.data(), line.data() + end, id);
    return parsed.ec == std::errc{} && parsed.ptr == line.data() + end ? id : -1;
}

bool equalFields(const Fields& a, const Fields& b, std::initializer_list<const char*> names) {
    for (auto key : names) {
        const auto first = a.find(key), second = b.find(key);
        if (first == a.end() || second == b.end() || first->second != second->second) return false;
    }
    return true;
}

Route rejected(std::string problem) {
    return {"", "", std::move(problem)};
}

} // namespace

Route compareRoutes(const std::string& dump, const std::string& sourcePath,
                    const std::string& mirrorLocation) {
    if (dump.empty()) return rejected("Android input diagnostics did not respond");
    std::vector<Hub> hubs;
    std::vector<Reader> readers;
    enum class Section { None, EventHub, Reader };
    Section section = Section::None;
    std::istringstream input(dump);
    std::string line;
    while (std::getline(input, line)) {
        const std::string body = trim(line);
        if (body.empty()) continue;
        size_t indent = line.find_first_not_of(' ');
        if (body == "Event Hub State:") { section = Section::EventHub; continue; }
        if (body.rfind("Input Reader State", 0) == 0) { section = Section::Reader; continue; }
        if (indent == 0) section = Section::None;
        const auto colon = body.find(':');
        if (colon == std::string::npos) continue;
        const std::string field = body.substr(0, colon);
        const std::string value = trim(body.substr(colon + 1));
        if (section == Section::EventHub) {
            if (indent == 4 && deviceId(body) >= 0) hubs.push_back({deviceId(body), {}});
            if (indent == 6 && !hubs.empty()) hubs.back().fields[field] = value;
        } else if (section == Section::Reader) {
            if (indent == 2 && body.rfind("Device ", 0) == 0) readers.emplace_back();
            if (readers.empty()) continue;
            if ((indent == 2 || indent == 4) && field == "EventHub Devices" &&
                value.size() >= 2 && value.front() == '[' && value.back() == ']') {
                std::string ids = value.substr(1, value.find(']') - 1);
                std::replace(ids.begin(), ids.end(), ',', ' ');
                std::istringstream list(ids);
                int id;
                while (list >> id) readers.back().hubs.push_back(id);
            }
            if (indent == 4) readers.back().fields[field] = value;
        }
    }

    Hub* physical = nullptr;
    Hub* mirror = nullptr;
    for (auto& hub : hubs) {
        if (hub.fields["Path"] == sourcePath) physical = &hub;
        if (hub.fields["Location"] == mirrorLocation) {
            if (mirror != nullptr) return rejected("more than one mirror matched");
            mirror = &hub;
        }
    }
    if (!physical) return rejected("Android did not list the physical button device");
    if (!mirror || physical == mirror) return rejected("Android did not register a separate forwarding device");
    if (physical->fields["Enabled"] != "true" || mirror->fields["Enabled"] != "true")
        return rejected("Android disabled one of the input routes");
    if (physical->fields["KeyLayoutFile"].empty() ||
        !equalFields(physical->fields, mirror->fields, {"KeyLayoutFile", "KeyCharacterMapFile", "ConfigurationFile"}))
        return rejected("the physical device and its mirror use different key layouts");
    if (!equalFields(physical->fields, mirror->fields, {"Classes", "Identifier"}))
        return rejected("Android classified the physical device and its mirror differently");
    auto readerFor = [&](int id) -> const Reader* {
        for (auto& reader : readers) {
            if (std::find(reader.hubs.begin(), reader.hubs.end(), id) != reader.hubs.end()) return &reader;
        }
        return nullptr;
    };
    const Reader* a = readerFor(physical->id);
    const Reader* b = readerFor(mirror->id);
    if (!a || !b) return rejected("Android did not attach both devices to InputReader");
    if (!equalFields(a->fields, b->fields, {"IsExternal", "Sources", "KeyboardType", "AssociatedDisplayPort"}))
        return rejected("the mirror's input source or display routing differs");
    // Some releases don't expose IsWaking or display binding. If present on one
    // side they must be present on both, with identical values.
    for (auto field : {"IsWaking", "IsVirtualDevice", "AssociatedDisplayUniqueIdByPort",
                       "AssociatedDisplayUniqueIdByDescriptor"}) {
        if ((a->fields.count(field) || b->fields.count(field)) &&
            !equalFields(a->fields, b->fields, {field})) return rejected(std::string("the mirror's ") + field + " differs");
    }
    if (mirror->fields["Path"].empty()) return rejected("Android did not report the mirror's device path");
    return {mirror->fields["Path"], physical->fields["KeyLayoutFile"], ""};
}

std::string describeMapping(const std::string& layoutText) {
    std::istringstream lines(layoutText);
    std::string line;
    while (std::getline(lines, line)) {
        std::istringstream tok(line.substr(0, line.find('#')));
        std::string keyword, scanCode, androidName;
        if (!(tok >> keyword >> scanCode >> androidName) || keyword != "key") continue;
        char* end = nullptr;
        const long parsed = std::strtol(scanCode.c_str(), &end, 0);
        if (*end == '\0' && parsed == kAiKey) return androidName;
    }
    return {};
}

} // namespace remap
