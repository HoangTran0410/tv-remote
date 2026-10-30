import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// TV Home, the launcher: its own VERSION (bump it only when app/tvhome or app/shared/src/home changes). It does not use :shared,
// which would bring the web page and the helper jars (1.8 MB) along: only HomeConfig.kt, the settings both sides read.
val appVersion = (System.getenv("VERSION_NAME") ?: file("VERSION").readText()).trim()
val keystore = file("signing.jks")                                    // git-ignored; build.sh creates one on the first build

android {
    namespace = "dev.tvremote.home"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.tvremote.home"
        minSdk = 21
        targetSdk = 34
        versionName = appVersion
        resValue("string", "released", LocalDate.now().toString())
        versionCode = appVersion.split(".").map { it.toInt() }.let { 100000 + it[0] * 10000 + it[1] * 100 + it[2] }
    }
    sourceSets["main"].java.srcDirs("../shared/src/home/kotlin", "../shared/src/guard/kotlin", "../shared/src/guard/android", "../shared/src/report/kotlin", "../shared/src/report/android", "../shared/src/tvui/kotlin", "../shared/src/marks/kotlin", "../shared/src/sign/kotlin", "../shared/src/selfupdate/kotlin", layout.buildDirectory.dir("generated/qr"))   // Release + SelfUpdate: its own updates
    sourceSets["main"].res.srcDirs("../shared/src/tvui/res")   // TvSheet (the dialogs' theme): ONE copy for the TV apps, as in :tv
    sourceSets["main"].assets.srcDirs(layout.buildDirectory.dir("generated/assets"))   // homeweb/ (the settings page), packed below
    signingConfigs {
        create("release") {
            storeFile = keystore
            storePassword = "tvhome"
            keyAlias = "tvhome"
            keyPassword = "tvhome"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true                                  // the shared icon set: only the few drawn here stay
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug { if (keystore.exists()) signingConfig = signingConfigs.getByName("release") }
    }
    lint { checkReleaseBuilds = false; checkOnly += "NewApi" }        // lintDebug: a call newer than minSdk 21 (Android 5-6 boxes crashed); release.sh runs it
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}
// Words: app/shared/i18n (langs.json + apps/<lang>/<module>.json) -> res/values-<lang> and the language list: app/gradle/i18n.gradle
extra["i18nApps"] = listOf("home", "tvui")
extra["i18nLangs"] = true
apply(from = "../gradle/i18n.gradle")

dependencies {
    testImplementation("junit:junit:4.13.2")                          // ./gradlew :tvhome:testDebugUnitTest
    testImplementation("org.json:json:20240303")
}

// The QR screen draws with Qr.kt, copied from :shared at build time (a srcDir of its folder would pull the web page / server in).
val copyQr = tasks.register<Copy>("copyQr") {
    from("../shared/src/main/kotlin/dev/tvremote/core/web/Qr.kt")
    into(layout.buildDirectory.dir("generated/qr/dev/tvremote/core/web"))
}
// "Manage from phone": TV Home serves TV Home's own settings page (home.html and what it loads) from assets/homeweb. The files are the
// ones TV Setup's web UI uses, copied from app/shared/web at build time, so the UI has ONE source; only the words the page needs travel.
val webDir = file("../shared/web")
val wordsDir = file("../shared/i18n")
val homeWebJs = listOf("theme", "i18n", "qr", "base", "home_link", "home_media", "homeweb", "target", "appicon", "quickkeys", "ui", "home_walls", "home_qr", "home_order", "home_folders", "photofit", "home_pin", "home")
val homeWebCss = listOf("base", "page", "apps", "remote", "auto", "kit")
val homeWebWords = listOf("home.", "homeweb.", "common.", "core.", "apps.", "pin.", "errors.pin_")   // pin.: the "Pin from YouTube" box (home_pin.js), its errors too
val packHomeWeb = tasks.register("packHomeWeb") {
    val out = layout.buildDirectory.dir("generated/assets/homeweb")
    inputs.dir(webDir); inputs.dir(wordsDir); outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively(); File(dir, "js").mkdirs(); File(dir, "css").mkdirs(); File(dir, "i18n").mkdirs()
        homeWebJs.forEach { File(webDir, "js/$it.js").copyTo(File(dir, "js/$it.js")) }
        homeWebCss.forEach { File(webDir, "css/$it.css").copyTo(File(dir, "css/$it.css")) }
        File(wordsDir, "langs.json").copyTo(File(dir, "i18n/langs.json"))                     // the list: the page reads it (js/i18n.js)
        @Suppress("UNCHECKED_CAST") val langs = (groovy.json.JsonSlurper().parse(File(wordsDir, "langs.json")) as Map<String, Any?>).keys
        for (l in langs) {
            @Suppress("UNCHECKED_CAST") val all = groovy.json.JsonSlurper().parse(File(wordsDir, "$l.json")) as Map<String, Any?>
            File(dir, "i18n/$l.json").writeText(groovy.json.JsonOutput.toJson(all.filterKeys { k -> homeWebWords.any { k.startsWith(it) } }))
        }
        val icons = File(webDir, "index.html").readText().substringAfter("<!-- icons:start -->").substringBefore("<!-- icons:end -->")
        File(dir, "home.html").writeText(File(webDir, "home.html").readText().replace("__ICONS__", icons))
    }
}
// The ready-made themes: the signed list is the copy used until the site's is read (only these two files of docs/home: the rest are pictures)
val packThemes = tasks.register<Copy>("packThemes") {
    from("../../docs/home") { include("themes.json", "themes.json.sig", "feeds.json", "feeds.json.sig", "links.json", "links.json.sig") }
    into(layout.buildDirectory.dir("generated/assets"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyQr, packHomeWeb, packThemes) }
