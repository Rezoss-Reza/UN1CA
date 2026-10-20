// Copyright (c) 2026 Rezoss
// SPDX-License-Identifier: GPL-3.0-or-later
#include "profile.hpp"
#include <cassert>
#include <unordered_map>
int main() {
    std::unordered_map<std::string,std::string> values;
    auto load=[&] { return unica_pif::loadProfile([&](const std::string &k,const std::string &d) {
        auto i=values.find(k); return i==values.end()?d:i->second; }); };
    auto p=load();
    assert(p.error.empty());
    assert(p.config.propMap.at("MODEL")=="Pixel 6a");
    assert(p.config.propMap.at("ID")=="ZP11.260918.007");
    assert(p.config.deviceInitialSdkInt=="32");
    values["persist.sys.pif.brand"]="null";
    values["persist.sys.pif.model"]="null";
    p=load(); assert(p.config.propMap.at("BRAND")=="google");
    assert(p.config.propMap.at("MODEL")=="Pixel 6a");
    values["persist.sys.pif.model"]="Explicit model";
    p=load(); assert(p.config.propMap.at("MODEL")=="Explicit model");
    values["persist.sys.pif.fingerprint"]="vendor/product/device:17/BUILD/123:user/release-keys";
    values["persist.sys.pif.model"]="null";
    p=load(); assert(p.error.empty());
    assert(!p.config.propMap.count("MODEL") && !p.config.propMap.count("MANUFACTURER"));
    values["persist.sys.pif.first_api_level"]="not-an-api";
    assert(!load().error.empty());
    values["persist.sys.pif.first_api_level"]="0";
    assert(!load().error.empty());
    values["persist.sys.pif.first_api_level"]="null";
    p=load(); assert(p.error.empty() && p.initialSdk==0 && p.config.deviceInitialSdkInt.empty());
    values["persist.sys.pif.security_patch"]="null";
    p=load(); assert(p.error.empty() && p.config.securityPatch.empty());
    values["persist.sys.pif.security_patch"]="2026-xx-05";
    assert(!load().error.empty());
    values.erase("persist.sys.pif.security_patch");
    values["persist.sys.pif.fingerprint"]="bad";
    assert(!load().error.empty());
    values["persist.sys.pif.fingerprint"]="google//device:17/BUILD/123:user/release-keys";
    assert(!load().error.empty());
    values["persist.sys.pif.fingerprint"]=unica_pif::kFingerprint;
    values["persist.sys.pif.spoof_build"]="false";
    values["persist.sys.pif.spoof_props"]="false";
    p=load(); assert(p.error.empty() && !p.spoofBuild && !p.spoofProps);
}
