#[cfg(any(windows, test))]
mod hardware;
#[cfg(windows)]
mod windows_host;
#[cfg(any(windows, test))]
mod wire;

fn main() {
    #[cfg(windows)]
    {
        let ipc = std::env::args().nth(1).as_deref() == Some("host-ipc");
        let result = windows_host::run(ipc);
        if let Err(error) = &result {
            if ipc {
                println!(
                    "error\t{}",
                    error.to_string().replace(['\r', '\n', '\t'], " ")
                );
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
