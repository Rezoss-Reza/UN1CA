// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.rezoss.poweron;

final class ScheduleRules {
    static final long WEEK = 7 * 86400L;
    static void validate(long on, long off, long now) {
        if (on < 1577836800L || on > 4102444740L || now < 1577836800L || now > 4102444740L)
            throw new IllegalArgumentException("Correct the phone's date and time first.");
        if (on % 60 != 0 || on - now < 120 || on - now > WEEK)
            throw new IllegalArgumentException("Choose a power-on time between 2 minutes and 7 days ahead.");
        if (off != 0 && (off < 1577836800L || off > 4102444740L
                || off % 60 != 0 || off - now < 120 || on - off < 180))
            throw new IllegalArgumentException("Choose power off at least 2 minutes ahead, and power on at least 3 minutes after power off.");
    }
    static boolean mayShutdown(ScheduleEngine.State state, String token, long now, long rtc) {
        return !state.uncertain && !state.token.isEmpty() && state.token.equals(token)
                && state.off > 0 && now >= state.off && now - state.off <= 60
                && state.on - now >= 120 && state.on - now <= WEEK
                && Math.abs((now - rtc) - state.offset) <= 10;
    }
    private ScheduleRules() {}
}
