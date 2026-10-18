// SPDX-License-Identifier: GPL-3.0-or-later
#include "../native/hold.h"
#include <assert.h>
int main() {
    Hold hold;
    assert(!hold.update(false, 0));
    assert(!hold.update(true, 100));
    assert(!hold.update(true, 699));
    assert(hold.update(true, 700));
    assert(!hold.update(false, 701));
    assert(!hold.update(true, 800));
    assert(!hold.update(true, 1399));
    assert(hold.update(true, 1400));
    Hold initiallyHeld;
    assert(!initiallyHeld.update(true, 2000));
    assert(initiallyHeld.update(true, 2600));
    Hold interrupted;
    assert(!interrupted.update(true, 0));
    assert(!interrupted.update(false, 599));
    assert(!interrupted.update(true, 600));
    assert(!interrupted.update(true, 1199));
}
