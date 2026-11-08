#[cfg(any(windows, test))]
mod hardware;
#[cfg(windows)]
mod windows_host;
#[cfg(any(windows, test))]
mod wire;

fn main() {
    #[cfg(windows)]
    {
        let result = windows_host::run();
        if let Err(error) = &result {
            eprintln!("{error}");
        }
        // The transfer session has already been torn down. Give app users time
        // to read diagnostics before the separate Windows console closes.
        if std::env::var_os("DROPFLOW_NEARBY_CONSOLE").is_some() {
            println!("Press Enter to close the Nearby Link lab.");
            let _ = std::io::stdin().read_line(&mut String::new());
        }
        if result.is_err() {
            std::process::exit(1);
        }
    }
    #[cfg(not(windows))]
    {
        eprintln!("This probe requires Windows and a Wi-Fi Direct-capable adapter.");
        std::process::exit(1);
    }
}
