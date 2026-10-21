#pragma once
#include <string>
#include <vector>

namespace fontbridge {
inline std::string normalize(const std::string& path) {
    if (path.empty() || path.front() != '/') return {};
    std::vector<std::string> parts;
    size_t offset = 1;
    while (offset <= path.size()) {
        size_t end = path.find('/', offset);
        if (end == std::string::npos) end = path.size();
        std::string part = path.substr(offset, end - offset);
        if (part == "..") { if (!parts.empty()) parts.pop_back(); }
        else if (!part.empty() && part != ".") parts.push_back(std::move(part));
        offset = end + 1;
    }
    std::string result;
    for (const auto& part : parts) { result += '/'; result += part; }
    return result.empty() ? "/" : result;
}
inline bool allowed(const std::string& path) {
    return path == "/system/etc/fonts.xml" || path == "/system/etc/font_fallback.xml"
        || path == "/system_ext/etc/fonts_base.xml" || path == "/system/system_ext/etc/fonts_base.xml"
        || path == "/product/etc/fonts_customization.xml" || path == "/system/product/etc/fonts_customization.xml"
        || path.rfind("/system/fonts/colorosmod/", 0) == 0;
}
}
