//! Command-line DropFlow peer for testing the transfer engine without the app.
//! It runs the same session code as the app and accepts every request, so use
//! it only on a network you control.
//!
//!   dfp_peer send <host> <port> [--legacy] <file-or-folder>...
//!   dfp_peer receive <folder> [--port N] [--legacy] [--count N]
//!
//! `--legacy` turns the DFP/1.1 features off, which makes this peer behave
//! like a release from before they existed.
//!
//! `--link host` or `--link join` offers a direct link whose "private
//! network" is simply this machine's loopback. It exercises the whole link
//! negotiation against another implementation without any radio.
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use std::time::Duration;

use dropflow_lib::transfer_manager::protocol::{
    perform_incoming_handshake, read_transfer_request, DirectCaps, DirectOffer,
};
use dropflow_lib::transfer_manager::session::{DirectLink, HostedLink, JoinedLink};
use dropflow_lib::transfer_manager::sender::build_job;
use dropflow_lib::transfer_manager::session::{
    receive_session, send_session, ReceiveSetup, SessionEvent, StagingRegistry,
};
use dropflow_lib::transfer_manager::transport::CancellableStream;
use dropflow_lib::transfer_manager::validation::validate_request;

/// A stand-in for a private network: both ends are on this machine.
struct LoopbackLink {
    caps: DirectCaps,
    listener: Option<TcpListener>,
}

impl LoopbackLink {
    fn from_flag(flag: Option<&str>) -> Option<Self> {
        let caps = match flag? {
            "host" => DirectCaps { host_rank: 2, join: false },
            "join" => DirectCaps { host_rank: 0, join: true },
            _ => return None,
        };
        Some(Self { caps, listener: None })
    }
}

impl DirectLink for LoopbackLink {
    fn caps(&self) -> DirectCaps {
        self.caps
    }
    fn host(&mut self, _tick: &mut dyn FnMut() -> Result<(), String>) -> Result<HostedLink, String> {
        Ok(HostedLink { ssid: "DIRECT-DF-loopback".into(), passphrase: "loopback-passphrase".into(), listen_port: None })
    }
    fn join(&mut self, _offer: &DirectOffer, _tick: &mut dyn FnMut() -> Result<(), String>) -> Result<JoinedLink, String> {
        let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        self.listener = Some(listener);
        Ok(JoinedLink { address: "127.0.0.1".into(), port })
    }
    fn accept(&mut self, wait: Duration) -> Result<Option<TcpStream>, String> {
        let listener = self.listener.as_ref().ok_or("not listening")?;
        let deadline = std::time::Instant::now() + wait;
        loop {
            match listener.accept() {
                Ok((socket, _)) => {
                    socket.set_nonblocking(false).map_err(|e| e.to_string())?;
                    return Ok(Some(socket));
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    if std::time::Instant::now() >= deadline {
                        return Ok(None);
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
                Err(error) => return Err(error.to_string()),
            }
        }
    }
    fn connect(&mut self, address: Option<&str>, port: u16) -> Result<TcpStream, String> {
        TcpStream::connect((address.unwrap_or("127.0.0.1"), port)).map_err(|e| e.to_string())
    }
    fn close(&mut self) {
        self.listener = None;
    }
}

#[allow(clippy::too_many_arguments)]
fn perf(direction: &str, files: usize, bytes: u64, resumed: u64, batch: bool, direct: bool, elapsed: Duration) {
    let secs = elapsed.as_secs_f64();
    println!(
        "[Perf] {direction} files={files} bytes={bytes} secs={secs:.3} mib_s={:.2} ms_per_file={:.1} batch={batch} resumed_bytes={resumed} direct={direct}",
        if secs > 0.0 { bytes as f64 / 1048576.0 / secs } else { 0.0 },
        if files > 0 { secs * 1000.0 / files as f64 } else { 0.0 },
    );
}

fn main() -> Result<(), String> {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let legacy = args.iter().any(|arg| arg == "--legacy");
    args.retain(|arg| arg != "--legacy");
    let mut option = |name: &str| -> Option<String> {
        let at = args.iter().position(|arg| arg == name)?;
        args.remove(at);
        (at < args.len()).then(|| args.remove(at))
    };
    let port = option("--port").and_then(|value| value.parse::<u16>().ok()).unwrap_or(0);
    let count = option("--count").and_then(|value| value.parse::<usize>().ok()).unwrap_or(usize::MAX);
    let link_flag = option("--link");
    let cancel = Arc::new(AtomicBool::new(false));

    match args.first().map(String::as_str) {
        Some("send") if args.len() >= 4 => {
            let port: u16 = args[2].parse().map_err(|_| "Invalid port".to_string())?;
            let job = build_job(
                format!("tx-{}", uuid::Uuid::new_v4()),
                "dfp-peer",
                "DropFlow test peer",
                "Desktop".into(),
                &args[3..],
                !legacy,
            )?;
            let mut socket = TcpStream::connect((args[1].as_str(), port)).map_err(|e| e.to_string())?;
            let mut stream = CancellableStream::new(&mut socket, cancel.clone()).map_err(|e| e.to_string())?;
            println!("sending {} files, {} bytes", job.files.len(), job.total_bytes());
            let mut link = LoopbackLink::from_flag(link_flag.as_deref());
            let link = link.as_mut().map(|link| link as &mut dyn DirectLink);
            let summary = send_session(&mut stream, &job, &cancel, link, &mut |event| {
                if let SessionEvent::Accepted(mode) = event {
                    println!("accepted batch={} resume={}", mode.batch, mode.resume);
                }
            })?;
            perf("send", job.files.len(), summary.bytes_sent, summary.bytes_resumed, summary.negotiated.batch, summary.direct, summary.elapsed);
            Ok(())
        }
        Some("receive") if args.len() >= 2 => {
            let folder = PathBuf::from(&args[1]);
            std::fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
            let folder = folder.canonicalize().map_err(|e| e.to_string())?;
            let listener = TcpListener::bind(("0.0.0.0", port)).map_err(|e| e.to_string())?;
            println!("listening on port {}", listener.local_addr().map_err(|e| e.to_string())?.port());
            let registry = StagingRegistry::default();
            for mut socket in listener.incoming().take(count).flatten() {
                let outcome = (|| -> Result<(), String> {
                    let mut stream =
                        CancellableStream::new(&mut socket, cancel.clone()).map_err(|e| e.to_string())?;
                    perform_incoming_handshake(&mut stream)?;
                    let meta = read_transfer_request(&mut stream)?;
                    validate_request(&meta)?;
                    println!("request from '{}': {} files, {} bytes", meta.sender_name, meta.total_files, meta.total_size_bytes);
                    let setup = ReceiveSetup { receive_dir: &folder, allow_features: !legacy, staging: &registry };
                    let mut link = LoopbackLink::from_flag(link_flag.as_deref());
                    let link = link.as_mut().map(|link| link as &mut dyn DirectLink);
                    let summary = receive_session(&mut stream, &meta, &setup, &cancel, link, &mut |_| {})
                        .map_err(|failure| format!("{} (resumable={})", failure.message, failure.resumable))?;
                    perf("receive", summary.files.len(), summary.bytes_received, summary.bytes_resumed, summary.negotiated.batch, summary.direct, summary.elapsed);
                    Ok(())
                })();
                if let Err(error) = outcome {
                    println!("failed: {error}");
                }
            }
            Ok(())
        }
        _ => Err("usage: dfp_peer send <host> <port> [--legacy] [--link host|join] <path>... | dfp_peer receive <folder> [--port N] [--legacy] [--count N] [--link host|join]".into()),
    }
}
