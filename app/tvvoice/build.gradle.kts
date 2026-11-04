import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.lang.Long as JLong
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// TV Setup Voice: speech heard on the TV itself, no Internet, for a TV that has no recognizer of its own (most boxes without Google).
// An Android RecognitionService that TV Setup's voice card (MicActivity) talks to like Google's, apart from TV Setup so that TV Setup
// stays small: 25 MB, put on the TV only when someone switches it on (MicVoice / VoiceAddon), taken off when switched off.
// No number of its own: TV Setup's (app/tvsetup/VERSION); signed with TV Setup's key, which TV Setup checks before installing it.
val appVersion = (System.getenv("VERSION_NAME") ?: file("../tvsetup/VERSION").readText()).trim()
// One APK and one package per language (the Vietnamese one must not carry the English model; a second language is added, not swapped): -Pvoicelang=vi (default, tv-voice.apk) or en (tv-voice-en.apk).
val voiceLang = (findProperty("voicelang") as String?) ?: "vi"
val models = mapOf(
    "vi" to listOf("https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip", "efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5"),
    "en" to listOf("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"))
require(voiceLang in models) { "voicelang must be one of ${models.keys}" }
val keystore = file("../tvsetup/signing.jks")                         // git-ignored; app/tvsetup/build.sh creates one on the first build

android {
    sourceSets["main"].java.srcDirs("src/protocol/kotlin")             // VoiceProtocol: ONE copy, TV Setup compiles it too
    namespace = "dev.tvremote.setup.voice"
    compileSdk = 35
    defaultConfig {
        applicationId = if (voiceLang == "vi") "dev.tvremote.setup.voice" else "dev.tvremote.setup.voice.$voiceLang"   // VoiceProtocol.addon: a package per language, so both can be on the TV
        minSdk = 21
        targetSdk = 34
        versionName = appVersion
        manifestPlaceholders["voiceLang"] = voiceLang
        versionCode = appVersion.split(".").map { it.toInt() }.let { 100000 + it[0] * 10000 + it[1] * 100 + it[2] }   // as TV Setup's
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }      // TV boxes; the x86 emulators cannot run it (6 MB more for no TV)
    }
    signingConfigs {
        create("release") {
            storeFile = keystore
            storePassword = "tvsetup"
            keyAlias = "tvsetup"
            keyPassword = "tvsetup"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false                                   // JNA finds Vosk's classes by name: kept whole (the code is small next to the model)
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug { if (keystore.exists()) signingConfig = signingConfigs.getByName("release") }
    }
    packaging { jniLibs { useLegacyPackaging = true } }               // Android 5-6 load .so files only from disk
    lint { checkReleaseBuilds = false; checkOnly += "NewApi" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47@aar")         // Apache-2.0
    implementation("net.java.dev.jna:jna:5.13.0@aar")                 // Apache-2.0 / LGPL-2.1
    testImplementation("junit:junit:4.13.2")                          // ./gradlew :tvvoice:testDebugUnitTest
}
extra["i18nApps"] = listOf("tvvoice")
apply(from = "../gradle/i18n.gradle")

// The model: Alpha Cephei's small Vietnamese one (Apache-2.0), fetched once at build time and checked against its SHA-256, never
// committed (51 MB). Everything is packed, Gr.fst too (24 MB, the free-speech language model: the second pass for a search the commands' list cannot
// hold, VoiceService.free); its word list, which the recognizer needs to build a list's graph, is also written out as graph/words.txt. assets/model/files.txt lists the
// files for VoiceModel.unpack; assets/model/VERSION is the hash, so a new model is unpacked again.
val modelUrl = models.getValue(voiceLang)[0]
val modelSha = models.getValue(voiceLang)[1]
val modelLayout = 4                                                   // raise when what is packed changes (3: fold.txt, likely words; 4: Gr.fst kept, for free speech): TVs unpack it again
val modelOut = layout.buildDirectory.dir("generated/model-$voiceLang")
val packModel = tasks.register("packModel") {
    val zip = layout.buildDirectory.file("model-dl/model-$voiceLang.zip")
    inputs.property("sha", modelSha)
    inputs.property("layout", modelLayout)
    outputs.dir(modelOut)
    doLast {
        val z = zip.get().asFile
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!z.isFile || sha(z) != modelSha) {
            z.parentFile.mkdirs()
            URI(modelUrl).toURL().openStream().use { i -> z.outputStream().use { i.copyTo(it) } }
            check(sha(z) == modelSha) { "the model's SHA-256 is not the pinned one: $modelUrl" }
        }
        val dir = modelOut.get().asFile.resolve("model")
        dir.deleteRecursively(); dir.mkdirs()
        val names = mutableListOf<String>()
        ZipFile(z).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val rel = e.name.substringAfter('/')                  // the zip's own top folder
                if (rel.isEmpty() || rel.contains("..")) continue
                if (rel == "graph/Gr.fst") { zf.getInputStream(e).use { words(it, dir.resolve("graph")) }; names += listOf("graph/words.txt", "graph/fold.txt") }   // and kept below: free speech needs it
                val f = dir.resolve(rel); f.parentFile.mkdirs()
                zf.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } }
                names += rel
            }
        }
        dir.resolve("files.txt").writeText(names.sorted().joinToString("\n") + "\n")
        dir.resolve("VERSION").writeText("${modelSha.take(16)}-$modelLayout")
    }
}
// OpenFst's header (little-endian): magic, type, arc type, version, flags, properties, start, states, arcs, then the input and output
// symbol tables when flags say so. The output table is the model's words, written as "word id" lines (Kaldi's words.txt).
// Also graph/fold.txt: "folded word<TAB>the words that fold to it", made here because folding 19,500 words took 12 s on a TV box,
// and only the likely ones: "mo" is 18 words (mở, mơ, mờ...) and a list with all of them heard "mở youtube" as "10 đú tu". How
// likely comes from the unigram costs in Gr.fst's own data (OpenGrm's NGramFst: the root state's futures): within [FOLD_SPAN] of
// the most likely, at most [FOLD_MAX].
val FOLD_SPAN = 2.5f
val FOLD_MAX = 4
fun words(input: InputStream, graph: File) {
    val d = DataInputStream(input.buffered())
    fun i32() = Integer.reverseBytes(d.readInt())
    fun i64() = JLong.reverseBytes(d.readLong())
    fun str() = String(ByteArray(i32()).also { d.readFully(it) }, Charsets.UTF_8)
    check(i32() == 2125659606) { "Gr.fst: not an OpenFst file" }
    check(str() == "ngram") { "Gr.fst: not an NGramFst" }
    str(); i32(); val flags = i32(); i64(); i64(); val states = i64(); i64()
    fun table(): List<Pair<String, Long>> { check(i32() == 2125658996); str(); i64(); return List(i64().toInt()) { str() to i64() } }
    if (flags and 1 != 0) table()
    check(flags and 2 != 0) { "Gr.fst: no word list" }
    val t = table()
    graph.mkdirs()
    graph.resolve("words.txt").writeText(t.joinToString("") { "${it.first} ${it.second}\n" })
    // NGramFst's data (ngram-fst.h, Init): counts, three LOUDS bitmaps, context and future labels, then (4-aligned) backoffs,
    // final costs, future costs. The root's futures are the 1 bits after the future bitmap's first 0: every word once.
    val b = ByteBuffer.wrap(d.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val ns = b.getLong(0); val nf = b.getLong(8); val nfin = b.getLong(16)
    check(ns == states) { "Gr.fst: NGramFst data not where expected" }
    fun words64(bits: Long) = (bits + 63) / 64 * 8
    var o = 24 + words64(ns * 2 + 1); val fut = o
    o += words64(nf + ns + 1) + words64(ns)
    o += (ns + 1) * 4; val fw = o; o += nf * 4
    o = (o + 3) and 3L.inv(); o += (ns + 1) * 4 + nfin * 4; val fp = o
    check(fp + nf * 4 <= b.capacity()) { "Gr.fst: NGramFst sizes do not add up" }
    fun bit(n: Long) = (b.getLong((fut + n / 64 * 8).toInt()) ushr (n % 64).toInt()) and 1L == 1L
    var z = 0L; while (bit(z)) z++
    val name = t.associate { it.second.toInt() to it.first }
    val cost = HashMap<String, Float>()
    var k = z + 1
    while (bit(k)) { val i = (k - z - 1).toInt(); name[b.getInt((fw + i * 4L).toInt())]?.let { cost[it] = b.getFloat((fp + i * 4L).toInt()) }; k++ }
    check(cost.size > 10_000) { "Gr.fst: only ${cost.size} unigrams read" }
    graph.resolve("fold.txt").writeText(t.map { it.first }.filter { it.isNotEmpty() && it[0] !in "<!#" }.groupBy(::fold)
        .filterKeys { it.isNotEmpty() }.entries.joinToString("") { (key, ws) ->
            val ranked = ws.sortedBy { cost[it] ?: Float.MAX_VALUE }
            val best = cost[ranked[0]] ?: Float.MAX_VALUE
            val keep = ranked.filter { (cost[it] ?: Float.MAX_VALUE) <= best + FOLD_SPAN }.take(FOLD_MAX).ifEmpty { ranked.take(1) }
            "$key\t${keep.joinToString(" ")}\n"
        })
}
// VoiceProtocol.fold, which a build script cannot call; VoiceModelTest checks the two agree.
fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('đ', 'd').replace('Đ', 'd')
    .lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
android.sourceSets["main"].assets.srcDirs(modelOut)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(packModel) }
