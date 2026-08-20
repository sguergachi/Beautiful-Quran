plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** CI exports unset secrets as empty strings; treat those as absent. */
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseKeystore = rootProject.file(env("RELEASE_KEYSTORE_FILE") ?: "release.keystore")
val timingContentBaseUrl = (env("TIMING_CONTENT_BASE_URL") ?: "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.beautifulquran"
    // API 37 ships as platforms/android-37.0; pin the minor so AGP resolves it.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.beautifulquran"
        minSdk = 30
        targetSdk = 37
        versionCode = 8
        versionName = "0.7"
        buildConfigField("String", "TIMING_CONTENT_BASE_URL", "\"$timingContentBaseUrl\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Store keystore; not committed. Credentials can be overridden from
        // the environment (CI secrets) and default to the local values.
        create("release") {
            storeFile = releaseKeystore
            storePassword = env("RELEASE_KEYSTORE_PASSWORD") ?: "division"
            keyAlias = env("RELEASE_KEY_ALIAS") ?: "beautifulquran"
            keyPassword = env("RELEASE_KEY_PASSWORD") ?: "division"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with the store keystore when it exists; otherwise fall back
            // to the debug keystore so CI and fresh clones can still
            // assembleRelease. Note the two signatures cannot update over each
            // other on a device — store builds need the real keystore present.
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "db"
        noCompress += "ttf"
        noCompress += "xz"
    }
    sourceSets.named("main") {
        // preBuild owns generation; a concrete path keeps Android Studio's
        // source-set model deterministic while the task dependency stays explicit.
        assets.directories.add(
            layout.buildDirectory.dir("generated/quranAssets").get().asFile.absolutePath,
        )
    }
    lint {
        // Media3's @UnstableApi opt-in trips lintVital on release builds; the
        // full lint task still reports it. CI ships release APKs, so keep
        // assembleRelease unblocked.
        checkReleaseBuilds = false
    }
}

baselineProfile {
    // Profiles are regenerated explicitly on representative hardware, then
    // committed. Release builds must remain deterministic and device-free.
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

val syncQuranDbAsset by tasks.registering(Sync::class) {
    val dbAsset = rootProject.layout.projectDirectory.file("data/quran.db")
    val lexiconAsset = rootProject.layout.projectDirectory.file("data/lexicon.db")
    val dictionaryAsset = rootProject.layout.projectDirectory.file("data/dictionary.db")
    from(dbAsset)
    // Lane / Wiktionary ship as .sqlite, not .db, so they fall outside
    // `noCompress` above and travel deflated. The *Database classes copy them
    // out of assets, where AssetManager inflates them.
    from(lexiconAsset) { rename { "lexicon.sqlite" } }
    from(dictionaryAsset) { rename { "dictionary.sqlite" } }
    into(layout.buildDirectory.dir("generated/quranAssets"))

    doLast {
        if (!dbAsset.asFile.isFile) {
            throw GradleException(
                "Missing canonical Quran database: ${dbAsset.asFile}. " +
                    "Run `python3 tools/build_db.py` from the repo root before building locally.",
            )
        }
        if (!lexiconAsset.asFile.isFile) {
            throw GradleException(
                "Missing canonical lexicon database: ${lexiconAsset.asFile}. " +
                    "Run `python3 tools/build_lexicon_db.py` from the repo root before building locally.",
            )
        }
        if (!dictionaryAsset.asFile.isFile) {
            throw GradleException(
                "Missing canonical dictionary database: ${dictionaryAsset.asFile}. " +
                    "Run `python3 tools/build_dictionary_db.py` from the repo root before building locally.",
            )
        }
    }
}

val syncQcfFonts by tasks.registering {
    val partsDir = layout.projectDirectory.dir("qcf-v2-fonts")
    val outDir = layout.buildDirectory.dir("generated/quranAssets/qcf-v2-fonts")
    inputs.dir(partsDir)
    outputs.dir(outDir)
    doLast {
        val dest = outDir.get().asFile
        dest.mkdirs()
        dest.listFiles()?.filter { it.extension == "ttf" }?.forEach { it.delete() }
        if (dest.resolve("QCF2001.qcf").isFile) return@doLast
        val parts = partsDir.asFile.listFiles { _, name ->
            name.startsWith("qcf-v2-fonts.tar.xz.part")
        }?.sortedBy { it.name }.orEmpty()
        if (parts.isEmpty()) {
            logger.warn("No QCF V2 font archive parts in ${partsDir.asFile}; mushaf pages fall back to Hafs.")
            return@doLast
        }
        val archive = temporaryDir.resolve("qcf-v2-fonts.tar.xz")
        archive.outputStream().use { out ->
            parts.forEach { part -> part.inputStream().use { it.copyTo(out) } }
        }
        val extract = ProcessBuilder(
            "bash", "-lc",
            "xz -dc '${archive.absolutePath}' | tar -x -C '${dest.parentFile.absolutePath}'",
        ).inheritIO().start()
        check(extract.waitFor() == 0) { "Failed to extract QCF V2 fonts" }
        check(dest.resolve("QCF2001.ttf").isFile) {
            "QCF extract did not produce ${dest.resolve("QCF2001.ttf")}"
        }
        // `.ttf` is noCompress — 208 MB stored raw. `.qcf` is the same SFNT
        // bytes so Typeface can still read them, and aapt deflates the pack.
        dest.listFiles { _, name -> name.endsWith(".ttf") }?.forEach { ttf ->
            check(ttf.renameTo(ttf.resolveSibling(ttf.nameWithoutExtension + ".qcf"))) {
                "Failed to rename ${ttf.name} for APK deflate"
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncQuranDbAsset, syncQcfFonts)
}

// DatabaseFingerprintTest reads these straight off disk, outside anything
// Gradle already tracks for the unit-test task. Without them declared, editing
// a database leaves the task UP-TO-DATE and the guard never runs — which is
// precisely the case it exists to catch.
tasks.withType<Test>().configureEach {
    listOf(
        "quran.db", "quran.db.sha256",
        "lexicon.db", "lexicon.db.sha256",
        "dictionary.db", "dictionary.db.sha256",
    )
        .forEach { asset ->
            inputs.file(rootProject.layout.projectDirectory.file("data/$asset"))
                .withPropertyName("dbFingerprint-$asset")
                .withPathSensitivity(PathSensitivity.NONE)
        }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.xz)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)

    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
