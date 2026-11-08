use serde::Serialize;
use tauri::{AppHandle, State};

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    phase: &'static str,
    message: String,
    ssid: Option<String>,
    passphrase: Option<String>,
}

impl Snapshot {
    fn new(phase: &'static str, message: impl Into<String>) -> Self {
        Self {
            phase,
            message: message.into(),
            ssid: None,
            passphrase: None,
        }
    }
}

#[derive(Default)]
pub struct NearbyLab {
    #[cfg(windows)]
    process: windows::Process,
}

#[tauri::command]
pub fn nearby_link_lab_status(state: State<'_, NearbyLab>) -> Result<Snapshot, String> {
    #[cfg(windows)]
    if option_env!("DROPFLOW_NEARBY_LAB") == Some("1") {
        return state.process.snapshot();
    }
    let _ = state;
    Ok(Snapshot::new(
        "unavailable",
        "Only available in the Windows Development preview.",
    ))
}

#[tauri::command]
pub fn set_nearby_link_lab(
    app: AppHandle,
    state: State<'_, NearbyLab>,
    enabled: bool,
) -> Result<Snapshot, String> {
    #[cfg(windows)]
    if option_env!("DROPFLOW_NEARBY_LAB") == Some("1") {
        return state.process.set(&app, enabled);
    }
    let _ = (app, state, enabled);
    Err("Only available in the Windows Development preview.".into())
}

#[cfg(any(windows, test))]
fn parse(line: &str) -> Option<Snapshot> {
    let (event, rest) = line.split_once('\t').unwrap_or((line, ""));
    match event {
        "ready" => {
            let (ssid, passphrase) = rest.split_once('\t')?;
            let hex = |value: &str, len| {
                value.len() == len
                    && value
                        .bytes()
                        .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
            };
            if !hex(ssid.strip_prefix("DIRECT-DF-")?, 10) || !hex(passphrase, 32) {
                return None;
            }
            Some(Snapshot {
                ssid: Some(ssid.to_owned()),
                passphrase: Some(passphrase.to_owned()),
                ..Snapshot::new(
                    "ready",
                    "Waiting for Android to join the temporary Wi-Fi link.",
                )
            })
        }
        "testing" if rest.is_empty() => Some(Snapshot::new(
            "testing",
            "Checking both transfer directions…",
        )),
        "verified" => {
            let millis: u32 = rest.parse().ok()?;
            Some(Snapshot::new(
                "verified",
                format!(
                    "Verified 16 MiB each way in {:.2} seconds.",
                    millis as f64 / 1000.0
                ),
            ))
        }
        "error" if !rest.is_empty() => Some(Snapshot::new(
            "error",
            rest.chars().take(350).collect::<String>(),
        )),
        _ => None,
    }
}

#[cfg(any(windows, test))]
mod windows {
    use super::{parse, Snapshot};
    use std::io::{BufRead, BufReader};
    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    use std::process::{ChildStdin, Command, Stdio};
    use std::sync::{Arc, Mutex};
    use std::thread;
    use std::time::{Duration, Instant};
    #[cfg(windows)]
    use tauri::{path::BaseDirectory, AppHandle, Manager};

    #[cfg(windows)]
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    #[cfg(windows)]
    const HOST_PATH: &str = "nearby-lab/dropflow-nearby-windows-lab.exe";

    struct Inner {
        snapshot: Snapshot,
        final_snapshot: Option<Snapshot>,
        stdin: Option<ChildStdin>,
        running: bool,
        stop_started: Option<Instant>,
    }

    impl Default for Inner {
        fn default() -> Self {
            Self {
                snapshot: Snapshot::new("off", "Off. Enable to start a one-session test."),
                final_snapshot: None,
                stdin: None,
                running: false,
                stop_started: None,
            }
        }
    }

    #[derive(Default)]
    pub struct Process(Arc<Mutex<Inner>>);

    impl Drop for Process {
        fn drop(&mut self) {
            if let Ok(mut inner) = self.0.lock() {
                inner.stdin.take();
                inner.stop_started = Some(Instant::now());
            }
        }
    }

    impl Process {
        pub fn snapshot(&self) -> Result<Snapshot, String> {
            self.0
                .lock()
                .map(|inner| inner.snapshot.clone())
                .map_err(|_| "Nearby Link state unavailable".into())
        }

        fn stop(&self) -> Result<Snapshot, String> {
            let mut inner = self.0.lock().map_err(|_| "Nearby Link state unavailable")?;
            inner.stdin.take();
            if inner.running {
                inner.stop_started.get_or_insert_with(Instant::now);
                inner.snapshot = Snapshot::new("stopping", "Closing the temporary Wi-Fi link…");
            } else {
                inner.snapshot = Inner::default().snapshot;
            }
            Ok(inner.snapshot.clone())
        }

        #[cfg(windows)]
        pub fn set(&self, app: &AppHandle, enabled: bool) -> Result<Snapshot, String> {
            if !enabled {
                return self.stop();
            }
            let executable = app
                .path()
                .resolve(HOST_PATH, BaseDirectory::Resource)
                .map_err(|err| err.to_string())?;
            // No shell, no PATH lookup, no user-supplied arguments or global events.
            self.start(
                Command::new(executable)
                    .arg("host-ipc")
                    .creation_flags(CREATE_NO_WINDOW),
            )
        }

        fn start(&self, command: &mut Command) -> Result<Snapshot, String> {
            let mut inner = self.0.lock().map_err(|_| "Nearby Link state unavailable")?;
            if inner.running {
                return Ok(inner.snapshot.clone());
            }
            let mut child = command
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()
                .map_err(|err| format!("Couldn't start Nearby Link: {err}"))?;
            let stdout = child.stdout.take().expect("configured stdout pipe");
            inner.stdin = child.stdin.take();
            inner.running = true;
            inner.stop_started = None;
            inner.final_snapshot = None;
            inner.snapshot =
                Snapshot::new("starting", "Starting a temporary Wi-Fi Direct session…");
            let reader_state = Arc::clone(&self.0);
            let reader = thread::spawn(move || {
                for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                    if let Some(snapshot) = parse(&line) {
                        if let Ok(mut inner) = reader_state.lock() {
                            if inner.stop_started.is_some() || inner.final_snapshot.is_some() {
                                continue;
                            }
                            match (inner.snapshot.phase, snapshot.phase) {
                                ("starting", "ready") | ("ready", "testing") => {
                                    inner.snapshot = snapshot
                                }
                                ("testing", "verified") | (_, "error") => {
                                    inner.final_snapshot = Some(snapshot);
                                    inner.snapshot = Snapshot::new(
                                        "stopping",
                                        "Closing the temporary Wi-Fi link…",
                                    );
                                }
                                _ => (),
                            }
                        }
                    }
                }
            });
            let state = Arc::clone(&self.0);
            thread::spawn(move || {
                let deadline = Instant::now() + Duration::from_secs(168);
                let mut forced = false;
                let succeeded = loop {
                    match child.try_wait() {
                        Ok(Some(status)) => break status.success(),
                        Ok(None) => (),
                        Err(_) => {
                            let _ = child.kill();
                            let _ = child.wait();
                            break false;
                        }
                    }
                    let stop_started = state.lock().ok().and_then(|inner| inner.stop_started);
                    if Instant::now() >= deadline
                        || stop_started.is_some_and(|stop| stop.elapsed() >= Duration::from_secs(8))
                    {
                        forced = true;
                        let _ = child.kill();
                        let _ = child.wait();
                        break false;
                    }
                    thread::sleep(Duration::from_millis(100));
                };
                let _ = reader.join();
                if let Ok(mut inner) = state.lock() {
                    inner.stdin.take();
                    inner.running = false;
                    let stopped = inner.stop_started.take().is_some();
                    let final_snapshot = inner.final_snapshot.take();
                    inner.snapshot = if forced {
                        Snapshot::new(
                            "error",
                            "The Windows lab did not close in time and was stopped.",
                        )
                    } else if stopped {
                        Inner::default().snapshot
                    } else {
                        final_snapshot
                            .filter(|snapshot| succeeded || snapshot.phase == "error")
                            .unwrap_or_else(|| {
                                Snapshot::new("error", "The Windows lab closed before it finished.")
                            })
                    };
                }
            });
            Ok(inner.snapshot.clone())
        }
    }

    #[cfg(all(test, unix))]
    mod tests {
        use super::*;

        fn wait_for(process: &Process, phase: &str) -> Snapshot {
            let deadline = Instant::now() + Duration::from_secs(4);
            loop {
                let current = process.snapshot().unwrap();
                if current.phase == phase {
                    return current;
                }
                assert!(Instant::now() < deadline, "stuck in {}", current.phase);
                thread::sleep(Duration::from_millis(20));
            }
        }

        #[test]
        fn stop_closes_stdin_and_clears_credentials() {
            let process = Process::default();
            let script = "printf 'ready\\tDIRECT-DF-12345abcde\\t0123456789abcdef0123456789abcdef\\n'; read ignored";
            process
                .start(Command::new("/bin/sh").args(["-c", script]))
                .unwrap();
            assert!(wait_for(&process, "ready").passphrase.is_some());
            assert!(process.stop().unwrap().passphrase.is_none());
            assert!(wait_for(&process, "off").passphrase.is_none());
        }

        #[test]
        fn only_successful_exit_can_publish_verification() {
            for (exit, expected) in [("0", "verified"), ("1", "error")] {
                let process = Process::default();
                let script = format!("printf 'ready\\tDIRECT-DF-12345abcde\\t0123456789abcdef0123456789abcdef\\ntesting\\nverified\\t900\\n'; exit {exit}");
                process
                    .start(Command::new("/bin/sh").args(["-c", &script]))
                    .unwrap();
                assert!(wait_for(&process, expected).passphrase.is_none());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credentials_only_appear_in_valid_ready_messages() {
        let ready = parse("ready\tDIRECT-DF-12345abcde\t0123456789abcdef0123456789abcdef").unwrap();
        assert_eq!(ready.phase, "ready");
        assert!(ready.passphrase.is_some());
        assert!(parse("ready\tother-network\t0123456789abcdef0123456789abcdef").is_none());
        assert!(parse("ready\tDIRECT-DF-12345abcde\tshort").is_none());
        for event in ["testing", "verified\t1200", "error\tDriver unavailable"] {
            let snapshot = parse(event).unwrap();
            assert!(snapshot.passphrase.is_none());
            assert!(snapshot.ssid.is_none());
        }
    }
}
