plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// TV Games: LibretroDroid (GPL-3) runs libretro cores; the NES core sits in jniLibs as lib<core>_libretro_android.so. Its own VERSION
// (bump it only when app/tvgames changes); it knows nothing of the other apps.
val appVersion = (System.getenv("VERSION_NAME") ?: file("VERSION").readText()).trim()
val keystore = file("signing.jks")                                    // git-ignored; build.sh creates one on the first build
android {
    namespace = "dev.tvremote.games"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.tvremote.games"
        minSdk = 21
        targetSdk = 34
        versionName = appVersion
        versionCode = appVersion.split(".").map { it.toInt() }.let { 100000 + it[0] * 10000 + it[1] * 100 + it[2] }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }       // 32-bit boxes too (an FPT Play 650 is armeabi-v7a only)
    }
    signingConfigs {
        create("release") {
            storeFile = keystore
            storePassword = "tvgames"
            keyAlias = "tvgames"
            keyPassword = "tvgames"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug { if (keystore.exists()) signingConfig = signingConfigs.getByName("release") }
    }
    packaging { jniLibs { useLegacyPackaging = true } }                // the core must be a real file: LibretroDroid dlopen()s a path
    lint { checkReleaseBuilds = false; checkOnly += "NewApi" }        // lintDebug: a call newer than minSdk 21 (Android 5-6 boxes crashed); release.sh runs it
    sourceSets["main"].java.srcDirs(layout.buildDirectory.dir("generated/qr"), "../shared/src/guard/kotlin", "../shared/src/guard/android", "../shared/src/report/kotlin", "../shared/src/report/android", "../shared/src/tvui/kotlin", "../shared/src/marks/kotlin", "../shared/src/sign/kotlin", "../shared/src/selfupdate/kotlin")   // Ed25519: games.json is signed; Release + SelfUpdate: its own updates
    sourceSets["main"].res.srcDirs("../shared/src/tvui/res")                 // tv_style.xml: ONE copy for the TV apps (app/DESIGN-TV.md)
    sourceSets["main"].assets.srcDirs("../../docs/games", layout.buildDirectory.dir("generated/padwords"))   // games.json + .sig: the copy used until the site's is read; the pad's words
    androidResources { ignoreAssetsPattern = "!roms" }                // the site's docs/games/roms are not packed
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}
// Words: app/shared/i18n (langs.json + apps/<lang>/<module>.json) -> res/values-<lang> and the language list: app/gradle/i18n.gradle
extra["i18nApps"] = listOf("games", "tvui")
extra["i18nLangs"] = true
apply(from = "../gradle/i18n.gradle")

dependencies {
    implementation("com.github.swordfish90:libretrodroid:0.13.2")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")                  // document-start script, limited to the game page's origin
    testImplementation("junit:junit:4.13.2")                          // ./gradlew :tvgames:testDebugUnitTest (ThumbNames)
}

// Qr.kt only needs android.graphics, SelfSigned.kt only java.security, so those files are copied from :shared (a srcDir of the
// whole folder would pull the web server in)
val copyQr = tasks.register<Copy>("copyQr") {
    from("../shared/src/main/kotlin/dev/tvremote/core/web/Qr.kt") { into("web") }
    from("../shared/src/main/kotlin/dev/tvremote/core/SelfSigned.kt")
    into(layout.buildDirectory.dir("generated/qr/dev/tvremote/core"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyQr) }

// The phone pad's words (web only, not Android strings): apps/<lang>/pad.json -> assets/pad/words/<lang>.json, served as /words.js by GameServer
val copyPadWords = tasks.register<Copy>("copyPadWords") {
    from("../shared/i18n/apps") { include("*/pad.json"); eachFile { path = path.substringBefore('/') + ".json" }; includeEmptyDirs = false }
    into(layout.buildDirectory.dir("generated/padwords/pad/words"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyPadWords) }
