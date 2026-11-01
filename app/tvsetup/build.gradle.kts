import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// This app's own version: the VERSION file next to this one; bump it only when this app (or app/shared) changes.
val appVersion = (System.getenv("VERSION_NAME") ?: file("VERSION").readText()).trim()
val keystore = file("signing.jks")                                    // git-ignored; build.sh creates one on the first build

android {
    namespace = "dev.tvremote.setup"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.tvremote.setup"
        minSdk = 21
        targetSdk = 34
        versionName = appVersion
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }      // Conscrypt's native code (+2 MB): TVs are ARM; elsewhere pairing is just not offered
        resValue("string", "released", LocalDate.now().toString())   // shown next to the version
        // 100000 +: above every code of the numbers before the restart at 0.1.0 (2.24.x was 22400), so Android installs over them
        versionCode = appVersion.split(".").map { it.toInt() }.let { 100000 + it[0] * 10000 + it[1] * 100 + it[2] }
    }
    sourceSets["main"].java.srcDirs("../shared/src/tvui/kotlin", "../shared/src/marks/kotlin", "../shared/src/selfupdate/kotlin")           // the TV apps' one look (app/DESIGN-TV.md): tokens + components, TvField
    sourceSets["main"].res.srcDirs("../shared/src/tvui/res")                // tv_style.xml: ONE copy for the TV apps
    sourceSets["main"].java.srcDirs("../shared/conscrypt")                  // ConscryptBackend: pairing with its own TV through Wireless debugging (a Chromecast with Google TV has no 5555)
    sourceSets["main"].java.srcDirs("../tvkeys/src/protocol/kotlin")        // KeysProtocol: what this app and its add-on agree on, ONE copy
    sourceSets["main"].java.srcDirs("../tvvoice/src/protocol/kotlin")       // VoiceProtocol: the same for TV Setup Voice
    // the add-on's APK (same variant, same key) rides inside this one as assets/tvkeys.apk: see packKeys below
    for (v in listOf("debug", "release")) sourceSets[v].assets.srcDirs(layout.buildDirectory.dir("generated/keys/$v"))
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
            isMinifyEnabled = true                                    // drops the unused part of the Kotlin library; names are kept (see proguard-rules.pro)
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../shared/proguard-rules.pro")
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug { if (keystore.exists()) signingConfig = signingConfigs.getByName("release") }   // same key, so a debug build installs over a release
    }
    androidResources {
        noCompress += "jar"
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!README.md"   // the default, and the notes beside the jars
    }
    lint { checkReleaseBuilds = false; checkOnly += "NewApi" }        // lintDebug: a call newer than minSdk 21 (Android 5-6 boxes crashed); release.sh runs it
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}
// Words: app/shared/i18n (langs.json + apps/<lang>/<module>.json) -> res/values-<lang> and the language list: app/gradle/i18n.gradle
extra["i18nApps"] = listOf("tvui")
apply(from = "../gradle/i18n.gradle")

// TV Setup Keys (app/tvkeys) holds the accessibility services, so this app declares none; its APK is built here and packed as an asset, never
// downloaded, so the two always have the same version. KeysAddon.kt puts it on the TV over ADB. No version of its own: this app's.
for (v in listOf("Debug", "Release")) {
    val pack = tasks.register<Copy>("packKeys$v") {
        dependsOn(":tvkeys:assemble$v")
        from(project(":tvkeys").layout.buildDirectory.dir("outputs/apk/${v.lowercase()}")) { include("*.apk"); rename { "tvkeys.apk" } }
        into(layout.buildDirectory.dir("generated/keys/${v.lowercase()}"))
    }
    tasks.matching { it.name == "merge${v}Assets" || it.name == "generate${v}LintReportModel" || it.name == "lintVitalAnalyze$v" }.configureEach { dependsOn(pack) }
}
tasks.withType<Test>().configureEach {                            // KeysAddonTest: the add-on is really in the APK, and signed alike
    systemProperty("tvkeys.dir", layout.buildDirectory.dir("generated/keys").get().asFile.path)
}
// one TV model's odd remote buttons (KeyList): the signed list is the copy used until the site's is read
val packKeyList = tasks.register<Copy>("packKeyList") {
    from("../../docs/setup") { include("keys.json", "keys.json.sig") }
    into(layout.buildDirectory.dir("generated/keylist"))
}
android.sourceSets["main"].assets.srcDirs(layout.buildDirectory.dir("generated/keylist"))
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(packKeyList) }
tasks.matching { it.name.startsWith("lint") && it.name.endsWith("Debug") }.configureEach { dependsOn("packKeysDebug") }   // lint reads the generated assets too
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn("packKeysDebug") }
tasks.matching { it.name == "testReleaseUnitTest" }.configureEach { dependsOn("packKeysRelease") }

dependencies {
    implementation(project(":shared"))
    implementation("org.conscrypt:conscrypt-android:2.7.0")           // TLS 1.3 + exported keying material for adb pairing, as the phone app
    testImplementation("junit:junit:4.13.2")                          // ./gradlew :tvsetup:testDebugUnitTest
    testImplementation("org.json:json:20240303")
}
