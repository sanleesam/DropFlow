//! One-line transfer timing for benchmarks. Logged locally only; never sent anywhere.
use std::time::Duration;

/// `[Perf] send files=12 bytes=1048576 secs=0.512 mib_s=1.95 ms_per_file=42.7`
/// Timing starts after approval, so a person's decision time is never counted.
pub(super) fn summary(direction: &str, files: usize, bytes: u64, elapsed: Duration) -> String {
    let secs = elapsed.as_secs_f64();
    let mib_s = if secs > 0.0 { bytes as f64 / (1024.0 * 1024.0) / secs } else { 0.0 };
    let per_file = if files > 0 { secs * 1000.0 / files as f64 } else { 0.0 };
    format!("[Perf] {direction} files={files} bytes={bytes} secs={secs:.3} mib_s={mib_s:.2} ms_per_file={per_file:.1}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn summary_is_stable_and_safe_for_empty_sessions() {
        assert_eq!(
            summary("send", 2, 2 * 1024 * 1024, Duration::from_millis(500)),
            "[Perf] send files=2 bytes=2097152 secs=0.500 mib_s=4.00 ms_per_file=250.0"
        );
        assert!(summary("receive", 0, 0, Duration::ZERO).contains("mib_s=0.00 ms_per_file=0.0"));
    }
}

/// Reproduces the DFP/1 per-file exchange over loopback TCP:
/// FileHeader → TransferAck → small DataChunk → FileComplete.
/// `cargo test --release --lib per_file_exchange -- --ignored --nocapture`
#[cfg(test)]
mod exchange_bench {
    use super::super::protocol::{
        encode_frame_header, read_frame_header, write_frame, FrameTag,
    };
    use std::{
        io::{Read, Write},
        net::{TcpListener, TcpStream},
        thread,
        time::{Duration, Instant},
    };

    const HEADER: &[u8] = br#"{"fileIndex":7,"relativePath":"Photos/IMG_4471.HEIC","sizeBytes":4096,"sha256Checksum":"","resumeOffset":0}"#;
    const ACK: &[u8] = br#"{"status":"ACCEPTED","resumeOffset":0}"#;
    const DONE: &[u8] = br#"{"fileIndex":7,"bytesWritten":4096,"sha256Checksum":"0000000000000000000000000000000000000000000000000000000000000000"}"#;

    fn send(socket: &mut TcpStream, tag: FrameTag, payload: &[u8], legacy: bool) {
        if legacy {
            socket.write_all(&encode_frame_header(tag, payload.len() as u32)).unwrap();
            socket.write_all(payload).unwrap();
        } else {
            write_frame(socket, tag, payload).unwrap();
        }
    }

    fn recv(socket: &mut TcpStream) -> FrameTag {
        let (tag, len) = read_frame_header(socket).unwrap();
        let mut payload = vec![0; len as usize];
        socket.read_exact(&mut payload).unwrap();
        tag
    }

    fn run(files: usize, legacy: bool) -> Duration {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut sender = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (mut receiver, _) = listener.accept().unwrap();
        sender.set_nodelay(!legacy).unwrap();
        receiver.set_nodelay(!legacy).unwrap();
        let worker = thread::spawn(move || {
            for _ in 0..files {
                assert_eq!(recv(&mut receiver), FrameTag::FileHeader);
                send(&mut receiver, FrameTag::TransferAck, ACK, legacy);
                assert_eq!(recv(&mut receiver), FrameTag::DataChunk);
                assert_eq!(recv(&mut receiver), FrameTag::FileComplete);
            }
        });
        let chunk = vec![7u8; 4096];
        let start = Instant::now();
        for _ in 0..files {
            send(&mut sender, FrameTag::FileHeader, HEADER, legacy);
            assert_eq!(recv(&mut sender), FrameTag::TransferAck);
            send(&mut sender, FrameTag::DataChunk, &chunk, legacy);
            send(&mut sender, FrameTag::FileComplete, DONE, legacy);
        }
        worker.join().unwrap();
        start.elapsed()
    }

    #[test]
    #[ignore = "timing benchmark; run explicitly"]
    fn per_file_exchange() {
        let files = 200;
        for (label, legacy) in [("legacy split writes, Nagle on", true), ("single-write frames, TCP_NODELAY", false)] {
            let mut trials: Vec<Duration> = (0..3).map(|_| run(files, legacy)).collect();
            trials.sort();
            let median = trials[1];
            println!(
                "{label:<34} {files} files  median {:>8.1} ms  ({:.2} ms/file)",
                median.as_secs_f64() * 1000.0,
                median.as_secs_f64() * 1000.0 / files as f64
            );
        }
    }
}
