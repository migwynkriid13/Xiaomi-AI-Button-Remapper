#pragma once

#include <string>

namespace remap {

struct Route {
    std::string mirrorPath;
    std::string layout;
};

// Returns empty paths if the input service cannot confirm both devices and
// their policy-relevant routing properties. No Binder-internal API is required.
Route compareRoutes(const std::string& dump, const std::string& sourcePath,
                    const std::string& mirrorLocation);
std::string describeMapping(const std::string& layoutText);

} // namespace remap

