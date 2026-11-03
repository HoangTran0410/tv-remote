import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// The computer's server on the same Kotlin core the two apps use (the computer app): the core's
// files are compiled here as they are, without the few that only run on Android (picture, sound, the on-TV helpers).
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("../shared/src/main/kotlin")
    sourceSets["main"].kotlin.srcDir("../shared/src/home/kotlin")     // HomeConfig: TV Home's settings
    sourceSets["main"].kotlin.srcDir("../shared/src/report/kotlin")   // Report + Scrub
    sourceSets["main"].kotlin.srcDir("../shared/src/sign/kotlin")     // Ed25519
    sourceSets["main"].kotlin.srcDir("../shared/src/tvapps/kotlin")    // TV Apps: the list, the APK checks
    sourceSets["main"].kotlin.srcDir("../shared/src/guard/kotlin")    // GuardLogic: the text of an error report (the Android part, Guard.kt, stays out)
    sourceSets["main"].kotlin.srcDir("../shared/conscrypt")           // ConscryptBackend: TLS 1.3 for Wireless debugging (core/AdbTls.kt)
}
// Words: app/shared/i18n (langs.json + apps/<lang>/<module>.json) -> res/values-<lang> and the language list: app/gradle/i18n.gradle
extra["i18nLangs"] = true
apply(from = "../gradle/i18n.gradle")
// Android only: decoding the picture and sound (TvAndroid, AudioOut), the QR bitmap. Meta and Caps, started on the TV, are Java
// (src/main/java) and reach the computer as assets/tvr-helper.jar, already dexed.
sourceSets["main"].kotlin.exclude("**/core/picture/TvAndroid.kt", "**/core/picture/AudioOut.kt", "**/core/web/Qr.kt", "**/core/Upright.kt", "**/core/CensusAndroid.kt", "**/core/SupportAndroid.kt")

dependencies {
    implementation("com.android.tools.build:apksig:8.9.2")           // AppCopy: signs the rewritten APK (the same jar the Android build uses)
    implementation("org.json:json:20240303")                         // on Android org.json is part of the system
    implementation("org.conscrypt:conscrypt-openjdk-uber:2.7.0")    // adb pairing: TLS 1.3 and the exported keying material (natives of every system inside)
    implementation("net.java.dev.jna:jna:5.15.0")                    // the window: Window.kt calls native/<system>/webview
    testImplementation("junit:junit:4.13.2")                         // ./gradlew :desktop:test
}

application {
    mainClass.set("dev.tvremote.desktop.MainKt")
    if ("mac" in System.getProperty("os.name").lowercase()) applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")   // --window: Cocoa
}

// TV Remote.app, TV Remote.exe or tv-remote: its own trimmed Java, the page and the files the server sends to TVs, all
// inside, so nothing has to be installed. jpackage only builds for the system it runs on (an arm64 Mac gives an arm64 Mac app):
// the GitHub workflow (.github/workflows/desktop.yml) runs this on each system for a release.
//   ./gradlew :desktop:packageApp   ->  app/desktop/build/package/<the app>  +  TV-Remote-<os>-<arch>.zip / .tar.gz
val os = System.getProperty("os.name").lowercase().let { if ("mac" in it) "mac" else if ("win" in it) "windows" else "linux" }
val arch = System.getProperty("os.arch").let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
val stage = layout.buildDirectory.dir("package-input")
val stageApp by tasks.registering(Sync::class) {
    dependsOn("installDist")
    into(stage)
    from(layout.buildDirectory.dir("install/desktop/lib"))
    into("res") {                                                     // the repo's own layout: Server.kt reads the same paths
        from("../..") { include("VERSION", "CHANGELOG.md") }
        from("../shared/web") { into("app/shared/web") }
        from("../shared/i18n") { exclude("apps/**"); into("app/shared/i18n") }
        from("../shared/assets") { include("scrcpy-server.jar", "tvr-helper.jar"); into("app/shared/assets") }
        from("native") { include(if (os == "mac") "mac/**" else "$os-$arch/**"); into("app/desktop/native") }   // the window (Window.kt)
    }
}
val appName = if (os == "linux") "tv-remote" else "TV Remote"         // Linux: a launcher name without a space
val packageOut = layout.buildDirectory.dir("package").get().asFile
val packageApp by tasks.registering(Exec::class) {
    dependsOn(stageApp)
    val version = rootDir.resolve("../../VERSION").readText().trim().let { v ->
        // a Mac bundle's number must not start with 0: 0.1.0 → 100.1.0, still going up (the app reads VERSION, not this)
        if (os == "mac") v.split(".").let { (it[0].toInt() + 100).toString() + "." + it.drop(1).joinToString(".") } else v
    }
    val jpackage = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get().metadata.installationPath
        .file(if (os == "windows") "bin/jpackage.exe" else "bin/jpackage").asFile
    doFirst { packageOut.deleteRecursively(); packageOut.mkdirs() }
    commandLine(listOf(jpackage.path, "--type", "app-image", "--name", appName, "--app-version", version, "--vendor", "tv-remote",
        "--input", stage.get().asFile.path, "--main-jar", "desktop.jar", "--main-class", "dev.tvremote.desktop.MainKt", "--dest", packageOut.path,
        "--add-modules", "java.base,java.logging,jdk.crypto.ec",           // TLS of the remote protocol (6467, 6466); JNA logs
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=2",
        "--java-options", "-Dtvr.res=\$APPDIR/res", "--java-options", "-Xmx256m") + when (os) {
            "mac" -> listOf("--java-options", "-XstartOnFirstThread",   // the window's Cocoa loop must run on the first thread
                "--icon", projectDir.resolve("icon.icns").path, "--mac-package-identifier", "dev.tvremote.desktop")
            "windows" -> listOf("--icon", projectDir.resolve("icon.ico").path)   // no --win-console: no black window; the log is server.log
            else -> listOf("--icon", rootDir.resolve("../shared/web/icon-512.png").path)
        })
    doLast {                                                          // without its languages listed, WebKit tells the page "en" on a Vietnamese Mac
        if (os != "mac") return@doLast
        val app = packageOut.resolve("TV Remote.app")
        val plist = app.resolve("Contents/Info.plist").path
        exec { commandLine("plutil", "-replace", "CFBundleLocalizations", "-json", "[\"vi\",\"en\"]", plist) }
        // the 🎤 key hears in the window, Video on the TV sees (native/mac.m): without these macOS ends the app the moment WebKit asks
        val ask = mapOf("en" to listOf("Speak to the TV: what you say becomes the words sent to it.", "Turns what you say to the TV into words.",
                "Video on the TV: your camera shown in a box on the TV."),
            "vi" to listOf("Nói với TV: lời bạn nói thành chữ gửi tới TV.", "Đổi lời bạn nói với TV thành chữ.", "Video trên TV: hình camera của bạn hiện trong khung trên TV."))
        exec { commandLine("plutil", "-replace", "NSMicrophoneUsageDescription", "-string", ask.getValue("en")[0], plist) }
        exec { commandLine("plutil", "-replace", "NSSpeechRecognitionUsageDescription", "-string", ask.getValue("en")[1], plist) }
        exec { commandLine("plutil", "-replace", "NSCameraUsageDescription", "-string", ask.getValue("en")[2], plist) }
        for ((l, w) in ask) app.resolve("Contents/Resources/$l.lproj").let { d ->
            d.mkdirs()
            d.resolve("InfoPlist.strings").writeText("NSMicrophoneUsageDescription = \"${w[0]}\";\nNSSpeechRecognitionUsageDescription = \"${w[1]}\";\nNSCameraUsageDescription = \"${w[2]}\";\n")
        }
        exec { commandLine("codesign", "--force", "--deep", "--sign", "-", app.path) }   // signed again over the changed Info.plist
    }
}
// the archive people download: zip on Mac (ditto keeps the app's signature and links) and Windows, tar.gz on Linux (modes)
val packageArchive by tasks.registering {
    dependsOn(packageApp)
    doLast {
        val base = "TV-Remote-$os-$arch"
        when (os) {
            "mac" -> exec { workingDir = packageOut; commandLine("ditto", "-c", "-k", "--keepParent", "TV Remote.app", "$base.zip") }
            "linux" -> exec { workingDir = packageOut; commandLine("tar", "-czf", "$base.tar.gz", appName) }
            else -> ZipOutputStream(packageOut.resolve("$base.zip").outputStream()).use { z ->   // not Ant's zip: it splits "TV Remote/**" at the space
                packageOut.resolve(appName).walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry(f.relativeTo(packageOut).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
    }
}
packageApp { finalizedBy(packageArchive) }
