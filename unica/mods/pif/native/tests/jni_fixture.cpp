// Copyright (c) 2026 Rezoss
// SPDX-License-Identifier: GPL-3.0-or-later
#include "vendor/pif_config.hpp"
#include <sys/system_properties.h>
#include <cstdlib>
#include <string>
struct prop_info { std::string name, value; };
extern "C" const prop_info *__system_property_find(const char *key) {
    const char *value=std::getenv(key);
    if(!value) return nullptr;
    static prop_info p; p={key,value}; return &p;
}
extern "C" void __system_property_read_callback(const prop_info *p,
    void (*callback)(void *,const char *,const char *,uint32_t),void *cookie) {
    callback(cookie,p->name.c_str(),p->value.c_str(),0);
}
extern "C" int __android_log_print(int,const char *,const char *,...) { return 0; }
namespace pif::property_virtualizer {
// Deliberately fail native virtualization to exercise fallback before any Build update.
bool install(const Config &) { return false; }
}
