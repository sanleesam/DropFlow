#[cfg(any(windows, test))]
mod hardware;
#[cfg(any(windows, test))]
mod link;
#[cfg(windows)]
mod windows_host;
#[cfg(windows)]
mod windows_link;
#[cfg(any(windows, test))]
mod wire;

fn main() {
    #[cfg(windows)]
    {
        // Driven by the app over pipes: errors are a line on stdout.
        let ipc = matches!(
            std::env::args().nth(1).as_deref(),
            Some("host-ipc" | "host-link")
        );
        let result = windows_host::run(ipc);
        if let Err(error) = &result {
            if ipc {
                println!("{}", link::Event::Error(&error.to_string()).line());
            } else {
                eprintln!("{error}");
            }
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
