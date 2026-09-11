// Tiny wrapper binary so Gradle can run:
//   cargo run --bin uniffi-bindgen -- generate --library <path/to/.so> --language kotlin
fn main() {
    uniffi::uniffi_bindgen_main()
}
