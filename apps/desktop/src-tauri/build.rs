fn main() {
    println!("cargo:rerun-if-env-changed=DROPFLOW_RELEASE_TAG");
    println!("cargo:rerun-if-env-changed=DROPFLOW_NEARBY_LAB");
    tauri_build::build()
}
