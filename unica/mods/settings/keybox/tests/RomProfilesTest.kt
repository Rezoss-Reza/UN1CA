// Copyright (c) 2026 Rezoss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.matrix.teesim

import org.json.JSONArray
import org.json.JSONObject

/** Pure routing tests with synthetic XML strings; never reads device keys or invokes keystore. */
fun main() {
    val h = Harvester.Record(false, ByteArray(32), ByteArray(32), true, 0, 1, 1, true,
        300, 300, 300, 170000, 202610, 20261005, 20261005, null,
        "samsung", "dm3q", "dm3q", "samsung", "SM-S918B", "", "", "", "", emptyMap(), 0)
    val apps = mapOf(10100 to listOf("test.one", "test.shared"), 10101 to listOf("test.two"))
    fun profile(id: String, packages: List<String>) = JSONObject().put("id", id)
        .put("keybox", "current").put("scope", "selected").put("apps", JSONArray(packages))
        .put("osVersion", "harvested").put("patchLevel", JSONObject()
            .put("system", "harvested").put("vendor", "harvested").put("boot", "harvested"))
    fun root(vararg profiles: JSONObject) = JSONObject().put("version", 1)
        .put("keyboxes", JSONObject()).put("profiles", JSONArray(profiles.toList()))
    fun resolve(root: JSONObject, xml: String? = "synthetic") = RomProfiles.resolve(root.toString(), xml, apps, h)
    fun rejected(root: JSONObject) {
        try { resolve(root) } catch (expected: Exception) { return }
        error("Ambiguous or invalid configuration accepted")
    }
    val single = resolve(root(profile("a", listOf("test.one")))).getJSONObject(0)
    check(single.getJSONArray("uids").toString() == "[10100]")
    check(single.getJSONArray("packages").length() == 2) // Full shared-UID expansion.
    check(single.getInt("osPatchLevel") == 202610)
    check(single.getInt("osVersion") == 170000)
    val fallback = profile("fallback", emptyList()).put("scope", "all")
    val split = resolve(root(fallback, profile("explicit", listOf("test.shared"))))
    check(split.getJSONObject(0).getJSONArray("uids").toString() == "[10101]")
    check(split.getJSONObject(1).getJSONArray("uids").toString() == "[10100]")
    rejected(root(profile("a", listOf("test.one")), profile("b", listOf("test.shared"))))
    rejected(root(profile("a", listOf("test.one")), profile("b", listOf("test.one"))))
    rejected(root(fallback, profile("b", emptyList()).put("scope", "all")))
    rejected(root(profile("a", emptyList()), profile("a", emptyList())))
    rejected(root(profile("bad name", emptyList())))
    rejected(root(profile("a", listOf("uid:1000"))))
    rejected(root(profile("a", emptyList()).put("keybox", "missing")))
    rejected(root(profile("a", emptyList()).put("mode", "invalid")))
    rejected(root(profile("a", emptyList()).put("patchLevel", JSONObject().put("system", "2026-02-31"))))
    check(resolve(root(profile("a", emptyList()))).getJSONObject(0).getJSONArray("uids").length() == 0)
    check(resolve(root(profile("a", listOf("test.absent")))).getJSONObject(0).getJSONArray("uids").length() == 0)
    check(resolve(root(profile("a", emptyList())), null).length() == 0)
    val named = root(profile("a", listOf("test.one")).put("keybox", "saved").put("mode", "generation"))
    named.getJSONObject("keyboxes").put("saved", "synthetic-signer")
    check(resolve(named, null).getJSONObject(0).getString("mode") == "generation")
    val identity = profile("a", emptyList()).put("deviceIds", JSONObject().put("model", "Custom model"))
    check(resolve(root(identity)).getJSONObject(0).getJSONObject("deviceIds").getString("model") == "Custom model")
    check(resolve(root(profile("a", listOf("test.one"))))
        .getJSONObject(0).getJSONArray("packageUsers").toString() == "[0,0]")
    println("ROM profile routing passed: UID grouping, fallback precedence, conflicts, absent apps, modes, levels and named signers")
}
