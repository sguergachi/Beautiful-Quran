plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** CI exports unset secrets as empty strings; treat those as absent. */
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

fun findReleaseKeystore(): File {
    env("RELEASE_KEYSTORE_FILE")?.let { return rootProject.file(it) }
    rootProject.file("release.keystore").takeIf(File::isFile)?.let { return it }

    // Linked worktrees do not inherit ignored files. Their .git marker points
    // into <primary>/.git/worktrees/<name>, so look beside that primary repo.
    val gitDir = rootProject.file(".git")
        .takeIf(File::isFile)
        ?.readText()
        ?.substringAfter("gitdir:", missingDelimiterValue = "")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(rootProject::file)
    return gitDir?.parentFile?.parentFile?.parentFile
        ?.resolve("release.keystore")
        ?.takeIf(File::isFile)
        ?: rootProject.file("release.keystore")
}

val releaseKeystore = findReleaseKeystore()

// The mushaf is the QCF V2 page faces or it is nothing: a build that ships
// without all 604 renders every leaf in the fallback Hafs face, which is the
// "misaligned mushaf" failure this pipeline once produced silently. So the
// sync task below fails the build rather than warning — the parts are
// git-tracked, so every clone and CI checkout has them. The output tree is
// deliberately NOT inside generated/quranAssets: syncQuranDbAsset is a Sync
// task, and a Sync deletes destination entries its source does not carry — it
// once wiped the whole qcf-v2-fonts/ subtree that syncQcfFonts had just
// extracted there, and the APK silently shipped a 110MB Hafs fallback with
// every mushaf page mis-set. Separate trees cannot race.
val qcfFontCount = 604
val qcfAssetsDir = layout.buildDirectory.dir("generated/qcfAssets/qcf-v2-fonts")

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
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Private release/upload keystore; never committed. On its owner's
        // machine it signs both variants so locally shared APKs update one
        // another. Fresh contributors still get the ordinary debug signer.
        create("release") {
            storeFile = releaseKeystore
            storePassword = env("RELEASE_KEYSTORE_PASSWORD") ?: "division"
            keyAlias = env("RELEASE_KEY_ALIAS") ?: "beautifulquran"
            keyPassword = env("RELEASE_KEY_PASSWORD") ?: "division"
        }
    }

    buildTypes {
        debug {
            signingConfig = if (releaseKeystore.isFile) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Contributor clones can assemble without the private key. The
            // publishing workflow separately requires and verifies it, so a
            // public APK can never silently fall back to debug signing.
            signingConfig = if (releaseKeystore.isFile) {
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
        // The QCF page faces live in their own tree, deliberately NOT inside
        // generated/quranAssets: syncQuranDbAsset is a Sync task, and a Sync
        // deletes destination entries its source does not carry — it once
        // wiped the whole qcf-v2-fonts/ subtree that syncQcfFonts had just
        // extracted there, and the APK silently shipped a 110MB Hafs fallback
        // with every mushaf page mis-set. Separate trees cannot race. The
        // *parent* is the assets root so the files keep their
        // qcf-v2-fonts/ prefix — MushafQcfFonts looks the faces up by that
        // path.
        assets.directories.add(
            qcfAssetsDir.get().asFile.parentFile.absolutePath,
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
    val searchConceptAsset = rootProject.layout.projectDirectory.file("data/search_concepts.json")
    val searchCandidateAsset = rootProject.layout.projectDirectory.file("data/search_concept_candidates.json")
    from(dbAsset)
    // Lane / Wiktionary ship as .sqlite, not .db, so they fall outside
    // `noCompress` above and travel deflated. The *Database classes copy them
    // out of assets, where AssetManager inflates them.
    from(lexiconAsset) { rename { "lexicon.sqlite" } }
    from(dictionaryAsset) { rename { "dictionary.sqlite" } }
    from(searchConceptAsset)
    from(searchCandidateAsset)
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
        if (!searchConceptAsset.asFile.isFile) {
            throw GradleException(
                "Missing search concept index: ${searchConceptAsset.asFile}. " +
                    "Run `python3 tools/build_search_concepts.py` from the repo root.",
            )
        }
        if (!searchCandidateAsset.asFile.isFile) {
            throw GradleException(
                "Missing fast search concept index: ${searchCandidateAsset.asFile}. " +
                    "Run `python3 tools/build_search_concepts.py` from the repo root.",
            )
        }
    }
}

val syncQcfFonts by tasks.registering {
    val partsDir = layout.projectDirectory.dir("qcf-v2-fonts")
    val outDir = qcfAssetsDir
    inputs.dir(partsDir)
    outputs.dir(outDir)
    doLast {
        val dest = outDir.get().asFile
        dest.mkdirs()
        dest.listFiles()?.filter { it.extension == "ttf" }?.forEach { it.delete() }
        // Idempotence gate is the count, not a single sentinel — a partial
        // extract (build killed mid-extract) leaves QCF2001.qcf plus <604
        // files and would otherwise pass forever via the old sentinel.
        val existing = dest.listFiles { _, name -> name.endsWith(".qcf") }.orEmpty()
        if (existing.size == qcfFontCount && dest.resolve("QCF2001.qcf").isFile) return@doLast
        existing.forEach { check(it.delete()) { "Failed to remove stale QCF face $it" } }
        val parts = partsDir.asFile.listFiles { _, name ->
            name.startsWith("qcf-v2-fonts.tar.xz.part")
        }?.sortedBy { it.name }.orEmpty()
        if (parts.isEmpty()) {
            throw GradleException(
                "No QCF V2 font archive parts in ${partsDir.asFile}. The mushaf " +
                    "would ship without its page faces; restore the parts " +
                    "(scripts/fetch_qcf_v2_fonts.sh) before building.",
            )
        }
        val archive = temporaryDir.resolve("qcf-v2-fonts.tar.xz")
        archive.outputStream().use { out ->
            parts.forEach { part -> part.inputStream().use { it.copyTo(out) } }
        }
        val extractors = ProcessBuilder.startPipeline(
            listOf(
                ProcessBuilder("xz", "-dc", archive.absolutePath),
                ProcessBuilder("tar", "-x", "-C", dest.parentFile.absolutePath),
            ),
        )
        check(extractors.all { it.waitFor() == 0 }) { "Failed to extract QCF V2 fonts" }
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
        // Partial extracts ship a half-dressed mushaf that is miserable to
        // diagnose from the glass. Count here, fail here.
        val fonts = dest.listFiles { _, name -> name.endsWith(".qcf") }.orEmpty()
        check(fonts.size == qcfFontCount) {
            "QCF extract produced ${fonts.size}/$qcfFontCount page fonts in $dest"
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
        "search_concepts.json",
        "search_concept_candidates.json",
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
