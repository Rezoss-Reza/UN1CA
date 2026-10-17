// SPDX-License-Identifier: GPL-3.0-or-later
#include "../native/alarm_logic.h"
#include <cassert>
#include <iostream>
#include <string>
template<class F> bool rejects(F fn) {
    try { fn(); return false; } catch (const std::runtime_error&) { return true; }
}
int main() {
    // Wall clock in 2026, RTC in 1970: conversion happens once, in the driver.
    const int64_t wall=1792238400, rtc=10123456, target=wall+600;
    poweron::validate(target,wall);
    assert(poweron::expectedRtc(target,wall,rtc)==rtc+600);
    assert(poweron::expectedRtc(target,wall+1,rtc)==((rtc+599)&~int64_t(3)));
    assert(std::string(poweron::payload(1792239000).data())=="1202610171210");
    assert(std::string(poweron::payload(0).data())=="0202002010101");
    assert(poweron::payload(target)[13]=='\0');
    assert(rejects([&]{poweron::validate(wall,wall);}));
    assert(rejects([&]{poweron::validate(wall-60,wall);}));
    assert(rejects([&]{poweron::validate(wall+60,wall);}));
    assert(rejects([&]{poweron::validate(target+1,wall);}));
    assert(rejects([&]{poweron::validate(wall+poweron::maxDelay+60,wall);}));
    poweron::validate(wall+120,wall);
    poweron::validate(wall+poweron::maxDelay,wall);
    assert(rejects([&]{poweron::validate(INT64_MIN,wall);}));
    assert(rejects([&]{poweron::validate(INT64_MAX,wall);}));
    assert(rejects([&]{poweron::validate(target,INT64_MIN);}));
    assert(poweron::cancelledRtc==1580518864);
    std::cout << "PASS: UTC encoding, RTC offset, bounds, cancellation command\n";
}
