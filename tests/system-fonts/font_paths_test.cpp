#include "font_paths.h"
#include <cassert>
#include <iostream>
int main() {
    using namespace fontbridge;
    assert(normalize("/system/fonts/colorosmod/abc.ttf") == "/system/fonts/colorosmod/abc.ttf");
    assert(allowed(normalize("/system/fonts/colorosmod/abc.ttf")));
    assert(allowed(normalize("/product/fonts/../../system/fonts/colorosmod/abc.ttf")));
    assert(allowed(normalize("/system/./etc/fonts.xml")));
    assert(allowed(normalize("/system//etc/font_fallback.xml")));
    assert(allowed(normalize("/system_ext/etc/fonts_base.xml")));
    assert(allowed(normalize("/system/system_ext/etc/fonts_base.xml")));
    assert(allowed(normalize("/system/product/etc/fonts_customization.xml")));
    assert(!allowed(normalize("/data/user/0/private/anything")));
    assert(!allowed(normalize("/system/fonts/DroidSansMono.ttf")));
    assert(!allowed(normalize("/system/fonts/colorosmod/../../../../data/private")));
    assert(normalize("relative/path.ttf").empty());
    std::cout << "PASS: 12 native font path checks\n";
}
