package dev.tvremote.core.ontv
import org.json.JSONArray
import org.json.JSONObject

/**
 * Health's readings beyond CPU and RAM: temperatures, the GPU's load, the network's traffic and the Bluetooth devices. Each
 * TV exposes them its own way (or not at all), so every reader takes what the shell printed and says nothing (null) rather
 * than guess; the page shows only what came back.
 */
object Sensors {
    // Qualcomm (kgsl), the generic gpu_busy of some kernels, MediaTek (ged): each a percentage, or busy and total cycles
    private const val GPU_FILES = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpubusy /sys/kernel/gpu/gpu_busy " +
            "/sys/module/ged/parameters/gpu_loading /sys/kernel/ged/hal/gpu_utilization"

    /** Read every 5 s: the thermal service's status and temperatures, the kernel's zones (when the HAL lists none), GPU files, traffic. */
    const val LIVE = "dumpsys thermalservice 2>/dev/null | sed -n '/^Thermal Status/p;/^Current temperatures from HAL/,/^Current cooling/p'; " +
            "printf '\\043~\\043\\n'; for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done 2>/dev/null; " +
            "printf '\\043~\\043\\n'; for f in $GPU_FILES; do [ -r \$f ] && echo \"\$f:\$(cat \$f 2>/dev/null)\"; done 2>/dev/null; " +
            "printf '\\043~\\043\\n'; cat /proc/net/dev"

    /** Read once a minute (dumpsys bluetooth_manager is long): the adapter, the paired devices, and the Bluetooth ones connected as input. */
    const val SLOW = "b=\"\$(dumpsys bluetooth_manager 2>/dev/null)\"; echo \"\$b\" | grep -m1 -E '^ *enabled:'; echo \"\$b\" | sed -n '/Bonded devices:/,/^ *\$/p'; " +   // one dump, read twice
            "echo '#in'; dumpsys input 2>/dev/null | awk '/^    [0-9]+: /{sub(/^    [0-9]+: /,\"\");n=\$0} /Identifier: bus=0x0005/{print n\"|\"\$0}'"

    // HAL temperature types that are temperatures (6-8 are battery current, voltage and percent)
    private val HAL_TYPES = mapOf(0 to "CPU", 1 to "GPU", 2 to "battery", 3 to "skin", 4 to "USB", 5 to "amplifier", 9 to "NPU", 10 to "TPU",
        11 to "display", 12 to "modem", 13 to "SoC", 14 to "Wi-Fi", 18 to "ambient")

    /** {status: 0 none … 6 shutdown, or absent; temps: [{name, c}], hottest first}; null when the TV tells nothing. */
    fun thermal(hal: String, zones: String): JSONObject? {
        val status = Regex("Thermal Status: (\\d)").find(hal)?.groupValues?.get(1)?.toInt()
        val temps = LinkedHashMap<String, Double>()
        for (m in Regex("Temperature\\{mValue=(-?[\\d.]+), mType=(-?\\d+), mName=([^,}]*)").findAll(hal)) {
            val c = m.groupValues[1].toDoubleOrNull() ?: continue
            val type = HAL_TYPES[m.groupValues[2].toInt()] ?: continue
            val name = m.groupValues[3].trim().ifEmpty { type }
            if (c > -30 && c < 150) temps.getOrPut(name) { c }
        }
        if (temps.isEmpty()) for (line in zones.lines()) {                 // older TVs: the kernel's zones, in thousandths of a degree
            val p = line.trim().split(Regex("\\s+"))
            if (p.size != 2) continue
            val v = p[1].toLongOrNull() ?: continue
            val c = if (v > 1000 || v < -1000) v / 1000.0 else v.toDouble()
            if (c > -30 && c < 150 && c != 0.0) temps.getOrPut(p[0]) { Math.round(c * 10) / 10.0 }
        }
        if (status == null && temps.isEmpty()) return null
        val o = JSONObject()
        if (status != null) o.put("status", status)
        o.put("temps", JSONArray(temps.entries.sortedByDescending { it.value }.take(8).map { JSONObject().put("name", it.key).put("c", it.value) }))
        return o
    }

    /** The GPU's load in percent from the first file that gave one, or null. */
    fun gpu(files: String): Int? {
        for (line in files.lines()) {
            val path = line.substringBefore(':'); val v = line.substringAfter(':', "").trim()
            val n = Regex("\\d+").findAll(v).map { it.value.toLong() }.toList()
            val pct = when {
                n.isEmpty() -> null
                path.endsWith("/gpubusy") -> if (n.size >= 2 && n[1] > 0) n[0] * 100 / n[1] else null   // busy and total cycles
                else -> n[0]
            }
            if (pct != null && pct in 0..100) return pct.toInt()
        }
        return null
    }

    /** Bytes received and sent so far, over every interface but loopback and tunnels (a tunnel's traffic is counted on Wi-Fi too). */
    fun netBytes(dev: String): LongArray? {
        var rx = 0L; var tx = 0L; var any = false
        for (line in dev.lines()) {
            val name = line.substringBefore(':', "").trim()
            if (name.isEmpty() || name == "lo" || name.startsWith("tun") || name.startsWith("dummy")) continue
            val f = line.substringAfter(':').trim().split(Regex("\\s+"))
            if (f.size < 9) continue
            rx += f[0].toLongOrNull() ?: continue; tx += f[8].toLongOrNull() ?: 0; any = true
        }
        return if (any) longArrayOf(rx, tx) else null
    }

    /** {on, devices: [{name, addr, connected}]}: paired devices, a connected input (remote, gamepad, keyboard) marked; null when unknown. */
    fun bluetooth(out: String): JSONObject? {
        val (bt, inputs) = (out + "\n#in\n").split("#in\n", limit = 2).let { it[0] to it[1] }
        val on = Regex("enabled:\\s*(\\w+)").find(bt)?.groupValues?.get(1) ?: return null
        val addr = Regex("([0-9A-FX]{2}(?::[0-9A-FX]{2}){5})", RegexOption.IGNORE_CASE)
        val devices = LinkedHashMap<String, JSONObject>()
        for (line in bt.substringAfter("Bonded devices:", "").lines()) {
            val m = addr.find(line) ?: continue
            val name = line.substring(m.range.last + 1).replace(Regex("\\[[^\\]]*\\]"), "").trim()
            devices[m.value.uppercase()] = JSONObject().put("name", name.ifEmpty { m.value }).put("addr", m.value.uppercase()).put("connected", false)
        }
        for (line in inputs.lines()) {
            if (!line.contains('|')) continue
            val name = line.substringBefore('|').replace(Regex("\\s*\\(aka .*\\)$"), "").trim()
            val a = Regex("bluetoothAddress=([0-9A-Fa-f:]{17})").find(line)?.groupValues?.get(1)?.uppercase()
            val known = devices[a] ?: devices.values.firstOrNull { it.optString("name") == name }
            if (known != null) known.put("connected", true)
            else devices[a ?: name] = JSONObject().put("name", name).put("addr", a ?: "").put("connected", true)
        }
        return JSONObject().put("on", on.equals("true", true)).put("devices", JSONArray(devices.values.sortedByDescending { it.getBoolean("connected") }))
    }
}
