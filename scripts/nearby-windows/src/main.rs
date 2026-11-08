#[cfg(windows)]
mod windows_host;
#[cfg(any(windows, test))]
mod wire;

fn main() {
    #[cfg(windows)]
    if let Err(error) = windows_host::run() {
        eprintln!("{error}");
        std::process::exit(1);
    }
    #[cfg(not(windows))]
    {
        eprintln!("This probe requires Windows, an enabled Wi-Fi adapter and enabled BLE.");
        std::process::exit(1);
    }
}
