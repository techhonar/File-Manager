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

android {
    namespace = "com.filemanager.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.filemanager.app"
        minSdk = 33
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // JNA ships .so files for every platform; keep only ours.
        jniLibs.useLegacyPackaging = false
    }

    sourceSets["main"].apply {
        // The generated UniFFI Kotlin lands here, and the Rust .so files here.
        java.srcDir(layout.buildDirectory.dir("generated/uniffi"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
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
    implementation(libs.coil.compose)
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

val rustDir = rootProject.layout.projectDirectory.dir("rust-core")
val jniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val bindingsDir = layout.buildDirectory.dir("generated/uniffi")

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

    doFirst {
        jniLibsDir.get().asFile.mkdirs()
    }
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate the Kotlin bindings from the compiled Rust library"
    dependsOn(cargoBuild)

    workingDir = rustDir.asFile
    inputs.dir(jniLibsDir)
    outputs.dir(bindingsDir)

    // Any ABI works as the bindgen input -- the interface is identical, and
    // bindgen reads the embedded metadata rather than the machine code.
    val soPath = jniLibsDir.get().asFile
        .resolve("arm64-v8a/libfilemanager_core.so").absolutePath

    commandLine(
        "cargo", "run", "--quiet", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", soPath,
        "--language", "kotlin",
        "--out-dir", bindingsDir.get().asFile.absolutePath,
    )
}

tasks.named("preBuild") {
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
