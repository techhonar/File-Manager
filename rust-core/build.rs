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

    // And anything else left undefined fails the build here, instead of the
    // app on the phone - the NDK's own advice for every shared library.
    println!("cargo:rustc-cdylib-link-arg=-Wl,--no-undefined");
}
