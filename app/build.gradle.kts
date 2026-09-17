plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read through `providers` rather than System.getenv() so Gradle's
// configuration cache tracks these as inputs instead of warning about them.
val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val keystoreAlias = providers.environmentVariable("KEY_ALIAS").orNull
val keystoreKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull

// CI injects these from the tag and the run number; local builds fall back.
val buildVersionName = providers.environmentVariable("VERSION_NAME").orNull ?: "0.1.0"
val buildVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1

// Where the Rust build drops its output. Declared up here because both the
// android source sets and the cargo tasks below need them.
val rustDir = rootProject.layout.projectDirectory.dir("rust-core")
val jniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val bindingsDir = layout.buildDirectory.dir("generated/uniffi")

android {
    namespace = "com.filemanager.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.filemanager.app"
        // Android 11. This is the real floor, not a preference: the app is
        // built around MANAGE_EXTERNAL_STORAGE, which arrived in API 30, and
        // nothing it uses needs anything newer - the highest calls are
        // isExternalStorageManager and StorageVolume.directory, both API 30.
        //
        // It was 33 for no better reason than that being the newest at the
        // time. That excluded every phone not updated past Android 12, and an
        // install on one fails with "There was a problem parsing the package",
        // which says nothing about the version being the cause.
        minSdk = 30
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Only the ABIs we actually build in Rust. arm64 covers every
            // real phone made in the last decade; x86_64 is for the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // Only declared when CI supplied a keystore. Without it the release
        // build produces app-release-unsigned.apk, which is deliberate: a
        // fork with no secrets should still build.
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword

                // Named explicitly rather than left to the plugin. Left alone
                // it picks schemes from minSdk, and at 30 that meant v2 only:
                // the published 0.2.1 carries a v2 block and nothing else.
                // That is legal - every Android 11 device verifies v2 - but it
                // leaves one scheme between the build and every installer that
                // has to read it. v1 costs about 30 KB of manifest digests and
                // v3 a few hundred bytes, and between them they cover the
                // older and the newer verifier, so a parser that dislikes one
                // has two others to fall back on.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests {
            // The network code under test touches no Android APIs, but a class
            // it shares a file with might - and an unstubbed framework call
            // throws "Stub!" rather than doing nothing.
            isReturnDefaultValues = true
        }
    }

    lint {
        // An API used above minSdk compiles fine and crashes at runtime, or -
        // as happened here - quietly narrows which phones can install at all.
        // NewApi is advisory by default; this makes it stop the build.
        error += "NewApi"
        abortOnError = true
    }

    buildFeatures {
        compose = true
        // Off by default since AGP 8. The About screen reads VERSION_NAME and
        // VERSION_CODE from it, which come from the tag CI builds from.
        buildConfig = true
    }
    // Strips the block the plugin otherwise stuffs into the APK signing
    // block: a Google-encrypted blob listing every dependency, which only
    // Play reads. We do not publish to Play, so it is dead weight, and it is
    // the one thing in the signed APK that is not a documented Android
    // structure - an installer that walks the signing block sees an ID it has
    // no definition for. Removing it also stops the dependency list leaving
    // the machine.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // BouncyCastle, MINA and FtpServer each ship their own copy of these,
        // and two files with the same path inside an APK is a packaging error
        // rather than something the build can pick between.
        resources.excludes += "/META-INF/{DEPENDENCIES,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        // Signature files from the signed jars. Left in, they would be checked
        // against the repackaged classes and fail.
        resources.excludes += "/META-INF/*.{SF,DSA,RSA}"
        // JNA ships .so files for every platform; keep only ours.
        jniLibs.useLegacyPackaging = false
    }

    sourceSets["main"].apply {
        // The Rust .so files. This one is enough on its own - the native libs
        // are packaged, not compiled.
        jniLibs.srcDir(jniLibsDir)
        // The generated bindings. Registered here AND on the Kotlin source
        // set below: the Kotlin Android plugin normally folds android's java
        // srcDirs into the Kotlin compilation, but doing both is free (Gradle
        // dedupes the directory) and removes the question entirely.
        java.srcDir(bindingsDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    // Network storage and the FTP server. Plain Java, no native code.
    implementation(libs.commons.net)
    implementation(libs.sshj)
    implementation(libs.smbj)
    implementation(libs.ftpserver.core)
    implementation(libs.ftplet.api)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.jna) { artifact { type = "aar" } }

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
}

// ---------------------------------------------------------------------------
// Rust build integration
//
// Two steps run before Kotlin compiles:
//   1. cargo ndk   -> builds libfilemanager_core.so for each Android ABI
//   2. uniffi-bindgen -> reads that .so and writes the Kotlin bindings
//
// Both are wired into preBuild, so `./gradlew assembleDebug` rebuilds Rust
// automatically. You never run cargo by hand.
// ---------------------------------------------------------------------------

/** Rust ABI triple -> the Android ABI directory name it must be packaged under. */
val abiTargets = mapOf(
    "aarch64-linux-android" to "arm64-v8a",
    "x86_64-linux-android" to "x86_64",
)

val cargoBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compile the Rust core into .so files for each Android ABI"

    workingDir = rustDir.asFile
    // Rebuild only when the Rust sources or manifest actually change.
    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    outputs.dir(jniLibsDir)

    val profile = "release"
    val args = mutableListOf("cargo", "ndk")
    abiTargets.values.forEach { abi -> args += listOf("-t", abi) }
    args += listOf("-o", jniLibsDir.get().asFile.absolutePath, "build", "--$profile")
    commandLine(args)

    // No doFirst here: a lambda in this script captures a reference to the
    // build script object, which the configuration cache cannot serialize.
    // cargo-ndk creates the -o directory itself, so nothing is needed anyway.
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate the Kotlin bindings from a host build of the Rust core"
    dependsOn(cargoBuild)

    workingDir = rustDir.asFile
    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    outputs.dir(bindingsDir)

    val outPath = bindingsDir.get().asFile.absolutePath

    // The bindings are generated from a HOST build of the crate, not from the
    // Android .so, even though the Android one is right there.
    //
    // uniffi-bindgen's --library mode discovers the interface by reading the
    // UNIFFI_META_* symbols out of a compiled library, and cargo-ndk runs
    // llvm-strip over every library it copies (src/cli.rs: `if !args.no_strip`)
    // - so the Android artifact never has them, whatever the cargo profile
    // says. Passing --no-strip would work but ships unstripped .so files in the
    // APK. The interface is platform-independent, so building the same crate
    // for the host gives identical bindings and keeps the APK small.
    //
    // Run through bash so the output can be checked: uniffi-bindgen exits 0
    // even when it finds no metadata and writes nothing at all. Trusting its
    // exit code means the build carries on and fails much later, in the Kotlin
    // compiler, with "Unresolved reference 'uniffi'" on every import - which
    // points at the wrong thing entirely. Fail here instead, where the cause
    // is obvious.
    commandLine(
        "bash", "-c",
        "set -e\n" +
            "cargo build --lib\n" +
            "cargo run --bin uniffi-bindgen -- generate " +
            "--library target/debug/libfilemanager_core.so " +
            "--language kotlin --out-dir '" + outPath + "'\n" +
            "find '" + outPath + "' -name '*.kt' | grep -q . || {\n" +
            "  echo 'ERROR: uniffi-bindgen produced no Kotlin bindings.' >&2\n" +
            "  echo 'The host library has no UNIFFI_META_* symbols. Check that' >&2\n" +
            "  echo 'nothing strips rust-core/target/debug/libfilemanager_core.so.' >&2\n" +
            "  exit 1\n" +
            "}\n",
    )
}

tasks.named("preBuild") {
    dependsOn(generateUniffiBindings)
}

// Also register the generated .kt with the Kotlin source set, so the Kotlin
// compile task sees them regardless of how the plugin treats android's java
// srcDirs.
kotlin {
    sourceSets.getByName("main").kotlin.srcDir(bindingsDir)
}

// preBuild alone does not guarantee ordering against the Kotlin compile
// task's input snapshot, so state the dependency directly.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiBindings)
}

// Keep `./gradlew clean` from leaving stale Rust artifacts behind.
tasks.named<Delete>("clean") {
    delete(jniLibsDir, bindingsDir)
}

val cargoTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the Rust core's own test suite on the host machine"
    workingDir = rustDir.asFile
    commandLine("cargo", "test")
}

tasks.named("check") {
    dependsOn(cargoTest)
}
