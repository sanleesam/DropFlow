fn main() {
    println!("cargo:rerun-if-env-changed=DROPFLOW_RELEASE_TAG");
    tauri_build::build()
}
