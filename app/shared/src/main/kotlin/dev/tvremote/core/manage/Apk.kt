package dev.tvremote.core.manage

/** What an APK says about itself, read here before it goes to the TV: its manifest, the CPUs it was built for, whether it fits. */
internal object Apk {
    // android:minSdkVersion, versionCode, versionName: what an attribute is when a shrunk manifest drops its name string
    private val ATTR_IDS = mapOf(0x0101020c to "minSdkVersion", 0x0101021b to "versionCode", 0x0101021c to "versionName")

    /**
     * From an APK's binary AndroidManifest.xml: "package", "versionName", "versionCode", "minSdkVersion" (what was found).
     * Reads the <manifest> element and the <uses-sdk> after it. Empty if unreadable. adbtools.apk_info does the same.
     */
    fun info(m: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val b = java.nio.ByteBuffer.wrap(m).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            fun u16(o: Int) = b.getShort(o).toInt() and 0xFFFF
            val strings = ArrayList<String>()
            var ids = IntArray(0)
            var o = u16(2)                                            // chunks start after the file header
            while (o + 8 <= m.size) {
                val kind = u16(o); val size = b.getInt(o + 4)
                if (kind == 0x0001) {                                 // string pool
                    val utf8 = b.getInt(o + 16) and 0x100 != 0; val start = o + b.getInt(o + 20)
                    for (i in 0 until b.getInt(o + 8)) {
                        var at = start + b.getInt(o + u16(o + 2) + 4 * i)
                        if (utf8) {
                            at += if (m[at].toInt() and 0x80 != 0) 2 else 1  // length in chars, then in bytes
                            var n = m[at].toInt() and 0x7F
                            if (m[at].toInt() and 0x80 != 0) { n = (n shl 8) or (m[at + 1].toInt() and 0xFF); at++ }
                            strings.add(String(m, at + 1, n, Charsets.UTF_8))
                        } else {
                            var n = u16(at)
                            if (n and 0x8000 != 0) { n = ((n and 0x7FFF) shl 16) or u16(at + 2); at += 2 }
                            strings.add(String(m, at + 2, 2 * n, Charsets.UTF_16LE))
                        }
                    }
                } else if (kind == 0x0180) {                          // resource ids of the attribute names
                    ids = IntArray((size - 8) / 4) { b.getInt(o + 8 + 4 * it) }
                } else if (kind == 0x0102) {                          // a start tag: <manifest>, then <uses-sdk> if any
                    val tag = strings[b.getInt(o + 20)]
                    val attrs = o + 16 + u16(o + 24); val step = u16(o + 26)
                    for (i in 0 until u16(o + 28)) {
                        val a = attrs + i * step
                        val n = b.getInt(a + 4)
                        val name = (if (n in ids.indices) ATTR_IDS[ids[n]] else null) ?: strings[n]
                        val raw = b.getInt(a + 8); val type = m[a + 15].toInt() and 0xFF; val data = b.getInt(a + 16).toLong() and 0xFFFFFFFFL
                        val value = if (raw != -1) strings[raw] else if (type == 0x10 || type == 0x11) data.toString() else null
                        if (value != null && (tag == "manifest" && name in setOf("package", "versionName", "versionCode") || tag == "uses-sdk" && name == "minSdkVersion"))
                            out[name] = value
                    }
                    if (tag != "manifest" && tag != "uses-sdk") break // <uses-sdk> comes first when it is there at all
                }
                o += if (size > 0) size else m.size
            }
        } catch (e: RuntimeException) {
        }
        return out
    }

    /** The package name in an APK's binary AndroidManifest.xml. Null if unreadable. */
    fun pkg(m: ByteArray): String? = info(m)["package"]

    /** The CPU types an APK has native code for, from its file names (lib/<abi>/x.so); empty when it runs anywhere. */
    fun abis(names: List<String>): List<String> =
        names.filter { it.startsWith("lib/") && it.endsWith(".so") && it.count { c -> c == '/' } >= 2 }.map { it.split("/")[1] }.toSortedSet().toList()

    /** Why this APK cannot go on a TV of this Android level and CPU, in the words the page translates; null when it can. */
    fun fits(info: Map<String, String>, abis: List<String>, sdk: Int, tvAbis: List<String>): String? {
        val need = info["minSdkVersion"]?.toIntOrNull() ?: 0
        if (sdk > 0 && need > sdk) return "apk needs sdk $need, the tv has sdk $sdk"
        if (abis.isNotEmpty() && tvAbis.isNotEmpty() && abis.none { it in tvAbis }) return "apk is built for ${abis.joinToString(",")}, the tv runs ${tvAbis.joinToString(",")}"
        return null
    }
}
