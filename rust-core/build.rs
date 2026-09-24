//! Links the C++ runtime that RARLAB's UnRAR needs on Android.
//!
//! unrar_sys compiles UnRAR's C++ sources and names a C++ runtime to link for
//! Linux, macOS and the BSDs - but Android is none of those to Rust, so there
//! it names nothing. The library still links, since a shared library may leave
//! symbols for the loader to find; it is the loader that fails, when the app
//! first loads it, and the app closes.
fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }

    // The NDK's static runtime, so no libc++_shared.so has to be packaged
    // beside the library: UnRAR is the only C++ in the app. The NDK ships it
    // in two halves. Named as plain libraries rather than static= ones, which
    // rustc would go looking for itself; the NDK's clang knows where they are.
    println!("cargo:rustc-link-lib=c++_static");
    println!("cargo:rustc-link-lib=c++abi");

    // unrar_sys also asks for libpthread, which Android does not have: threads
    // are part of its C library, so the NDK ships no libpthread for the linker
    // to find, and the link fails. An empty archive answers the request.
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR").expect("cargo sets OUT_DIR"));
    std::fs::write(out.join("libpthread.a"), b"!<arch>\n").expect("write empty libpthread.a");
    println!("cargo:rustc-link-search=native={}", out.display());

    // And anything else left undefined fails the build here, instead of the
    // app on the phone - the NDK's own advice for every shared library.
    println!("cargo:rustc-cdylib-link-arg=-Wl,--no-undefined");
}
