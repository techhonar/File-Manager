# File Manager

An Android file manager in the style of Samsung's My Files, built with
**Kotlin** (UI) and **Rust** (everything that touches many files at once).

## How the two languages split the work

| Rust (`rust-core/`) | Kotlin (`app/`) |
| --- | --- |
| Recursive directory scans and sizes | Jetpack Compose UI |
| Global search with filters | Permissions, MediaStore, intents |
| Storage analysis by category | Navigation and screen state |
| Zip create / list / extract | Single-file rename, mkdir |
| Duplicate detection (blake3) | Thumbnail loading |
| Recycle bin (move / restore / purge) | Sharing via FileProvider |

The two halves talk through **UniFFI**: types are declared once in Rust and the
Kotlin bindings are generated at build time. There is no hand-written JNI.

```rust
// rust-core/src/scanner.rs
#[uniffi::export]
pub fn list_dir(path: String, show_hidden: bool, sort: SortOptions)
    -> Result<Vec<FileEntry>>
```

```kotlin
// generated - you never edit this
@Throws(FileException::class)
fun listDir(path: String, showHidden: Boolean, sort: SortOptions): List<FileEntry>
```

## Status

The Rust core is complete and tested (`cargo test`, 14 tests passing).
The Kotlin app is written but **has never been compiled** - the Android
toolchain is not installed yet. Expect to fix compile errors on the first
build; see *Setup* below.

## Setup on Arch Linux

Nothing Android-related is installed yet. These are the steps, in order.

### 1. JDK

```bash
sudo pacman -S jdk21-openjdk
```

### 2. Swap pacman's `rust` for `rustup`

Cross-compiling to Android needs `rustup target add`, which the pacman `rust`
package does not support. The two packages conflict, so this replaces one with
the other. Same compiler, different manager.

```bash
sudo pacman -Rdd rust          # remove without touching dependents
sudo pacman -S rustup
rustup default stable
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
```

### 3. Android Studio

```bash
paru -S android-studio
```

Then launch it once and use the SDK Manager to install:

- Android SDK Platform 35
- Android SDK Build-Tools
- **NDK (Side by side)** - required; the Rust build fails without it
- Android SDK Platform-Tools (gives you `adb`)
- Android Emulator

### 4. Environment

Add to `~/.bashrc`:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"   # fill in the version
```

### 5. Gradle wrapper

The wrapper JAR is not in this repo (it is a binary, and generating it needs a
JDK). Generate it once locally:

```bash
gradle wrapper --gradle-version 8.11.1
```

Or just open the project in Android Studio, which creates it for you. CI does
not need it - the release workflow installs Gradle 8.11.1 directly and calls
`gradle` rather than `./gradlew`. Keep the two versions in step.

## Building

```bash
./gradlew assembleDebug        # builds Rust, generates bindings, builds the APK
./gradlew installDebug         # installs to the connected device or emulator
./gradlew check                # runs the Rust test suite too
```

Rust rebuilds automatically as part of the Gradle build - you never run
`cargo` by hand for the app. To work on the core alone:

```bash
cd rust-core && cargo test     # runs natively on Linux, no emulator needed
```

This is the fastest loop by a wide margin. All the filesystem logic is
testable on the host, so most bugs never reach a phone.

## Releases

Tagging `v*` runs `.github/workflows/release.yml`, which builds the Rust core
for both Android ABIs, runs its test suite, builds a signed APK and publishes
it as a GitHub Release.

```bash
git tag v0.1.0
git push origin v0.1.0
```

A manual run (Actions tab, *Run workflow*) builds the APK and leaves it as a
build artifact without publishing a release - useful for testing the pipeline.

### One-time signing setup

Without a keystore the workflow still succeeds, but produces an **unsigned**
APK that will not install on a phone. To get installable builds, create a key
once and store it in the repository secrets.

Creating the key needs `keytool`, which comes with the JDK:

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias filemanager \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep `release.jks` somewhere safe and **never commit it**. Losing it means you
can never ship an update to anyone who installed an earlier APK - Android
refuses to upgrade an app signed with a different key.

Then add four repository secrets under
*Settings -> Secrets and variables -> Actions*:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `filemanager` |
| `KEY_PASSWORD` | the key password you chose |

### Versioning

`versionName` comes from the tag (`v1.2.3` builds `1.2.3`). `versionCode` uses
the workflow run number, so it always increases - Android rejects an update
whose `versionCode` is not higher than the installed one.

Local builds fall back to `0.1.0` / `1`.

## Permissions

The app requests `MANAGE_EXTERNAL_STORAGE` ("All files access"), which is what
lets Rust walk real paths like `/storage/emulated/0` directly. The user grants
it in Settings rather than through a dialog - see `data/StoragePermission.kt`.

Google Play restricts this permission to apps whose core function requires it.
A file manager qualifies but needs a declaration form at submission.
Sideloading is unaffected.

## Project layout

```
rust-core/src/
  types.rs        FileEntry, FileCategory, sorting
  errors.rs       the one error type crossing the boundary
  cancel.rs       CancelToken + ProgressListener
  scanner.rs      list_dir, dir_size, copy, delete
  search.rs       parallel search with filters
  categories.rs   extension -> category, home screen queries
  storage.rs      usage breakdown, largest files
  archive.rs      zip create / list / extract
  trash.rs        recycle bin
  dedup.rs        duplicate detection

app/src/main/java/com/filemanager/app/
  data/           FileRepository (the only place Kotlin calls Rust)
  viewmodel/      one per screen
  ui/screens/     Home, Browser, Search, Storage, Trash, Permission
  ui/components/  FileRow, StorageBar, icons, dialogs
```

## Known gaps

- Grid view toggles state but the browser still renders a list.
- Copy/move run in a ViewModel scope, so leaving the screen cancels them; they
  belong in a foreground Service (the manifest permissions are already there).
- Only zip is supported for archives. rar/7z would need another crate.
- No SD-card SAF fallback - the app assumes all-files access was granted.
