//! End-to-end tests of the session core over real loopback TCP: both
//! protocol generations, every old/new pairing, interruption and resume,
//! cancellation in both directions, and a latency benchmark.
use std::fs;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::thread;
use std::time::{Duration, Instant};

use super::protocol::*;
use super::session::*;
use super::transport::CancellableStream;

struct Sandbox {
    root: PathBuf,
}

impl Sandbox {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!("dropflow-session-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(root.join("src")).unwrap();
        fs::create_dir_all(root.join("dst")).unwrap();
        Self { root }
    }
    fn src(&self) -> PathBuf {
        self.root.join("src")
    }
    fn dst(&self) -> PathBuf {
        self.root.join("dst")
    }
    fn job(&self, files: &[(&str, Vec<u8>)], offer_features: bool) -> SendJob {
        let files = files
            .iter()
            .map(|(name, bytes)| {
                let path = self.src().join(name);
                fs::create_dir_all(path.parent().unwrap()).unwrap();
                fs::write(&path, bytes).unwrap();
                SendFile { path, relative_path: name.to_string(), size_bytes: bytes.len() as u64 }
            })
            .collect();
        SendJob {
            session_id: format!("tx-{}", uuid::Uuid::new_v4()),
            sender_id: "sender-device".into(),
            sender_name: "Sender".into(),
            sender_platform: "Test".into(),
            files,
            offer_features,
        }
    }
    fn assert_received(&self, files: &[(&str, Vec<u8>)]) {
        for (name, bytes) in files {
            let saved = fs::read(self.dst().join(name)).unwrap_or_else(|e| panic!("{name}: {e}"));
            assert!(saved == *bytes, "{name} differs from what was sent");
        }
        assert!(!self.dst().join(".dropflow-staging").exists(), "staging folder was left behind");
    }
}

impl Drop for Sandbox {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

fn bytes(len: usize, seed: u8) -> Vec<u8> {
    (0..len).map(|i| (i as u64).wrapping_mul(2654435761).rotate_left(seed as u32) as u8 ^ seed).collect()
}

/// What sits between the two peers.
#[derive(Clone, Copy)]
enum Link {
    Direct,
    /// Drop the connection after this many sender-to-receiver bytes.
    CutAfter(usize),
    /// Hold every segment for this long in each direction.
    Delay(Duration),
}

fn pump(mut from: TcpStream, mut to: TcpStream, link: Link) {
    thread::spawn(move || {
        let mut buffer = vec![0u8; 64 * 1024];
        match link {
            Link::Delay(delay) => {
                let (tx, rx) = mpsc::channel::<(Instant, Vec<u8>)>();
                let writer = thread::spawn(move || {
                    for (at, data) in rx {
                        thread::sleep((at + delay).saturating_duration_since(Instant::now()));
                        if to.write_all(&data).is_err() {
                            return;
                        }
                    }
                    let _ = to.shutdown(Shutdown::Write);
                });
                while let Ok(count) = from.read(&mut buffer) {
                    if count == 0 || tx.send((Instant::now(), buffer[..count].to_vec())).is_err() {
                        break;
                    }
                }
                drop(tx);
                let _ = writer.join();
            }
            _ => {
                let mut budget = match link {
                    Link::CutAfter(limit) => limit,
                    _ => usize::MAX,
                };
                loop {
                    let count = match from.read(&mut buffer) {
                        Ok(0) | Err(_) => break,
                        Ok(count) => count.min(budget),
                    };
                    if to.write_all(&buffer[..count]).is_err() {
                        break;
                    }
                    budget -= count;
                    if budget == 0 {
                        let _ = from.shutdown(Shutdown::Both);
                        let _ = to.shutdown(Shutdown::Both);
                        return;
                    }
                }
                let _ = to.shutdown(Shutdown::Write);
            }
        }
    });
}

/// A connected (sender, receiver) socket pair, optionally through a proxy.
fn connect(link: Link) -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let sender = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    let (near, _) = listener.accept().unwrap();
    if matches!(link, Link::Direct) {
        return (sender, near);
    }
    let far_listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let far = TcpStream::connect(far_listener.local_addr().unwrap()).unwrap();
    let (receiver, _) = far_listener.accept().unwrap();
    for socket in [&near, &far, &sender, &receiver] {
        socket.set_nodelay(true).unwrap();
    }
    // Only the data direction is cut; the reverse pump ends when its sockets close.
    let reverse = if matches!(link, Link::CutAfter(_)) { Link::Direct } else { link };
    pump(near.try_clone().unwrap(), far.try_clone().unwrap(), link);
    pump(far, near, reverse);
    (sender, receiver)
}

struct Run {
    sent: Result<SendSummary, String>,
    received: Result<ReceiveSummary, ReceiveFailure>,
}

struct Options<'a> {
    allow_features: bool,
    link: Link,
    registry: &'a StagingRegistry,
    /// Cancel the sender / receiver once this many session bytes have moved.
    cancel_sender_at: Option<u64>,
    cancel_receiver_at: Option<u64>,
    /// Cancel the receiver once every file is verified and saving has begun.
    cancel_receiver_while_saving: bool,
    /// What each side can do for a direct link (none by default).
    sender_link: Option<LoopbackLink>,
    receiver_link: Option<LoopbackLink>,
}

impl<'a> Options<'a> {
    fn new(registry: &'a StagingRegistry) -> Self {
        Self {
            allow_features: true,
            link: Link::Direct,
            registry,
            cancel_sender_at: None,
            cancel_receiver_at: None,
            cancel_receiver_while_saving: false,
            sender_link: None,
            receiver_link: None,
        }
    }
}

fn run(job: &SendJob, dst: &Path, mut options: Options<'_>) -> Run {
    let (mut sender_socket, mut receiver_socket) = connect(options.link);
    let mut sender_link = options.sender_link.take();
    let mut receiver_link = options.receiver_link.take();
    thread::scope(|scope| {
        let receiver = scope.spawn(|| {
            let cancel = Arc::new(AtomicBool::new(false));
            let mut stream = CancellableStream::new(&mut receiver_socket, cancel.clone()).unwrap();
            perform_incoming_handshake(&mut stream).unwrap();
            let meta = read_transfer_request(&mut stream).unwrap();
            super::validation::validate_request(&meta).unwrap();
            let setup = ReceiveSetup {
                receive_dir: dst,
                allow_features: options.allow_features,
                staging: options.registry,
            };
            let limit = options.cancel_receiver_at;
            let while_saving = options.cancel_receiver_while_saving;
            let link = receiver_link.as_mut().map(|link| link as &mut dyn DirectLink);
            receive_session(&mut stream, &meta, &setup, &cancel, link, &mut |event| {
                if while_saving && matches!(event, SessionEvent::Finishing) {
                    cancel.store(true, Ordering::Relaxed);
                }
                if let (SessionEvent::Progress(progress), Some(limit)) = (&event, limit) {
                    if progress.session_bytes >= limit {
                        cancel.store(true, Ordering::Relaxed);
                    }
                }
            })
        });
        let cancel = Arc::new(AtomicBool::new(false));
        let sent = {
            let mut stream = CancellableStream::new(&mut sender_socket, cancel.clone()).unwrap();
            let limit = options.cancel_sender_at;
            let link = sender_link.as_mut().map(|link| link as &mut dyn DirectLink);
            send_session(&mut stream, job, &cancel, link, &mut |event| {
                if let (SessionEvent::Progress(progress), Some(limit)) = (&event, limit) {
                    if progress.session_bytes >= limit {
                        cancel.store(true, Ordering::Relaxed);
                    }
                }
            })
        };
        let _ = sender_socket.shutdown(Shutdown::Both);
        Run { sent, received: receiver.join().unwrap() }
    })
}

/// A "private network" that is really loopback: it exercises everything
/// about a direct link except the radio.
struct LoopbackLink {
    caps: DirectCaps,
    /// As host: accept the joiner's connection (otherwise the joiner listens).
    host_listens: bool,
    fail_host: bool,
    fail_join: bool,
    /// One Wi-Fi connection only: joining means leaving the shared network.
    leaves: bool,
    /// As host: able to find a joiner that left (`connect_joined`).
    finds_joiner: bool,
    listener: Option<TcpListener>,
    closed: Arc<AtomicBool>,
}

impl LoopbackLink {
    fn new(host_rank: u8, join: bool) -> Self {
        Self {
            caps: DirectCaps { host_rank, join },
            host_listens: false,
            fail_host: false,
            fail_join: false,
            leaves: false,
            finds_joiner: true,
            listener: None,
            closed: Arc::new(AtomicBool::new(false)),
        }
    }
    fn listen(&mut self) -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let port = listener.local_addr().unwrap().port();
        self.listener = Some(listener);
        port
    }
}

impl DirectLink for LoopbackLink {
    fn caps(&self) -> DirectCaps {
        self.caps
    }
    fn host(&mut self, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<HostedLink, String> {
        tick()?;
        if self.fail_host {
            return Err("this adapter cannot create a network".into());
        }
        let listen_port = self.host_listens.then(|| self.listen());
        Ok(HostedLink { ssid: "DIRECT-DF-test".into(), passphrase: "secret".into(), listen_port })
    }
    fn join(&mut self, offer: &DirectOffer, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<JoinedLink, String> {
        tick()?;
        assert_eq!((offer.ssid.as_str(), offer.passphrase.as_str()), ("DIRECT-DF-test", "secret"));
        if self.fail_join {
            return Err("could not join the network".into());
        }
        let port = match (&self.listener, offer.host_port) {
            (Some(listener), _) => listener.local_addr().unwrap().port(), // already listening (leaving)
            (None, None) => self.listen(),
            (None, Some(_)) => 0,
        };
        Ok(JoinedLink { address: "127.0.0.1".into(), port })
    }
    fn leaves_network(&self) -> bool {
        self.leaves
    }
    fn listen(&mut self) -> Result<u16, String> {
        Ok(LoopbackLink::listen(self))
    }
    fn accepts_leaving(&self) -> bool {
        self.finds_joiner
    }
    fn connect_joined(&mut self, port: u16, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<TcpStream, String> {
        tick()?;
        TcpStream::connect(("127.0.0.1", port)).map_err(|e| e.to_string())
    }
    fn accept(&mut self, wait: Duration) -> Result<Option<TcpStream>, String> {
        let listener = self.listener.as_ref().ok_or("not listening")?;
        let deadline = Instant::now() + wait;
        loop {
            match listener.accept() {
                Ok((socket, _)) => {
                    socket.set_nonblocking(false).unwrap();
                    return Ok(Some(socket));
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    if Instant::now() >= deadline {
                        return Ok(None);
                    }
                    thread::sleep(Duration::from_millis(10));
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
        self.closed.store(true, Ordering::Relaxed);
    }
}

fn mixed_files() -> Vec<(&'static str, Vec<u8>)> {
    let mut files = vec![
        ("report.pdf", bytes(300 * 1024, 1)),
        ("empty.txt", Vec::new()),
        ("Photos/2026/IMG_0001.HEIC", bytes(700 * 1024 + 1, 2)),
        ("Photos/2026/IMG_0002.HEIC", bytes(256 * 1024, 3)),
        ("Notes/日本語 – résumé.txt", bytes(17, 4)),
        ("tiny.bin", vec![9]),
    ];
    const NAMES: [&str; 6] = ["src/a.rs", "src/b.rs", "src/c.rs", "src/deep/d.rs", "src/deep/e.rs", "src/deep/f.rs"];
    for (index, name) in NAMES.iter().enumerate() {
        files.push((name, bytes(100 + index * 977, 10 + index as u8)));
    }
    files
}

#[test]
fn batch_transfer_delivers_every_file_exactly() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let files = mixed_files();
    let job = sandbox.job(&files, true);
    let result = run(&job, &sandbox.dst(), Options::new(&registry));
    let sent = result.sent.unwrap();
    let received = result.received.unwrap();
    assert_eq!(sent.negotiated, Negotiated { batch: true, resume: true });
    assert_eq!(received.negotiated, sent.negotiated);
    assert_eq!(sent.bytes_sent, job.total_bytes());
    assert_eq!(received.bytes_received, job.total_bytes());
    assert_eq!((sent.bytes_resumed, received.bytes_resumed), (0, 0));
    assert_eq!(received.files.len(), files.len());
    for file in &received.files {
        assert_eq!(file.sha256_checksum.len(), 64);
        assert!(Path::new(&file.final_path).is_file());
    }
    sandbox.assert_received(&files);
}

#[test]
fn every_pairing_of_old_and_new_peers_interoperates() {
    for (offer, allow) in [(false, false), (false, true), (true, false), (true, true)] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let files = mixed_files();
        let job = sandbox.job(&files, offer);
        let mut options = Options::new(&registry);
        options.allow_features = allow;
        let result = run(&job, &sandbox.dst(), options);
        let sent = result.sent.unwrap_or_else(|e| panic!("offer={offer} allow={allow}: {e}"));
        let received = result.received.unwrap();
        assert_eq!(sent.negotiated.batch, offer && allow);
        assert_eq!(received.negotiated.batch, offer && allow);
        sandbox.assert_received(&files);
    }
}

/// A sender written against the original protocol, with its split writes.
/// Asserts the bytes a new receiver puts on the wire for it are unchanged.
#[test]
fn new_receiver_speaks_unchanged_dfp1_to_an_original_sender() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let (mut socket, mut receiver_socket) = connect(Link::Direct);
    let dst = sandbox.dst();
    thread::scope(|scope| {
        let receiver = scope.spawn(|| {
            let cancel = AtomicBool::new(false);
            let mut stream =
                CancellableStream::new(&mut receiver_socket, Arc::new(AtomicBool::new(false))).unwrap();
            perform_incoming_handshake(&mut stream).unwrap();
            let meta = read_transfer_request(&mut stream).unwrap();
            let setup = ReceiveSetup { receive_dir: &dst, allow_features: true, staging: &registry };
            receive_session(&mut stream, &meta, &setup, &cancel, None, &mut |_| {})
        });

        let frame = |socket: &mut TcpStream| {
            let (tag, len) = read_frame_header(socket).unwrap();
            let mut payload = vec![0; len as usize];
            socket.read_exact(&mut payload).unwrap();
            (tag, payload)
        };
        let split = |socket: &mut TcpStream, tag: FrameTag, payload: &[u8]| {
            write_frame_header(socket, tag, payload.len() as u32).unwrap();
            socket.write_all(payload).unwrap();
        };
        split(&mut socket, FrameTag::Heartbeat, HELLO_HANDSHAKE.as_bytes());
        assert_eq!(frame(&mut socket), (FrameTag::Heartbeat, HELLO_ACK.as_bytes().to_vec()));
        // Exactly what the original desktop sender put in a request, local path included.
        split(&mut socket, FrameTag::TransferRequest, br#"{"sessionId":"old-1","senderId":"old","senderName":"Old PC","senderPlatform":"Windows","totalFiles":2,"totalSizeBytes":5,"files":[{"fileIndex":0,"relativePath":"a.txt","sizeBytes":3,"sha256Checksum":"","finalPath":"C:\\Users\\x\\a.txt"},{"fileIndex":1,"relativePath":"dir/b.txt","sizeBytes":2,"sha256Checksum":"","finalPath":"C:\\Users\\x\\dir\\b.txt"}]}"#);
        assert_eq!(frame(&mut socket), (FrameTag::TransferAccept, b"ACCEPTED".to_vec()));
        for (index, (name, data, digest)) in [
            ("a.txt", &b"abc"[..], "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            ("dir/b.txt", &b"hi"[..], ""), // the original protocol allowed a missing digest
        ]
        .into_iter()
        .enumerate()
        {
            let header = format!(r#"{{"fileIndex":{index},"relativePath":"{name}","sizeBytes":{},"sha256Checksum":"","resumeOffset":0}}"#, data.len());
            split(&mut socket, FrameTag::FileHeader, header.as_bytes());
            assert_eq!(
                frame(&mut socket),
                (FrameTag::TransferAck, br#"{"status":"ACCEPTED","resumeOffset":0}"#.to_vec())
            );
            split(&mut socket, FrameTag::DataChunk, data);
            let done = format!(r#"{{"fileIndex":{index},"bytesWritten":{},"sha256Checksum":"{digest}"}}"#, data.len());
            split(&mut socket, FrameTag::FileComplete, done.as_bytes());
        }
        split(&mut socket, FrameTag::TransferComplete, b"old-1");
        assert_eq!(frame(&mut socket), (FrameTag::TransferAck, b"ACK".to_vec()));
        let summary = receiver.join().unwrap().unwrap();
        assert_eq!(summary.negotiated, Negotiated::default());
    });
    sandbox.assert_received(&[("a.txt", b"abc".to_vec()), ("dir/b.txt", b"hi".to_vec())]);
}

/// A receiver written against the original protocol. Asserts a new sender
/// keeps the original order: header, wait for the acknowledgement, data.
#[test]
fn new_sender_speaks_unchanged_dfp1_to_an_original_receiver() {
    let sandbox = Sandbox::new();
    let job = sandbox.job(&[("a.txt", b"abc".to_vec()), ("dir/b.txt", bytes(300_000, 7))], true);
    let (mut sender_socket, mut socket) = connect(Link::Direct);
    let session_id = job.session_id.clone();
    let peer = thread::spawn(move || {
        let frame = |socket: &mut TcpStream| {
            let (tag, len) = read_frame_header(socket).unwrap();
            let mut payload = vec![0; len as usize];
            socket.read_exact(&mut payload).unwrap();
            (tag, payload)
        };
        assert_eq!(frame(&mut socket), (FrameTag::Heartbeat, HELLO_HANDSHAKE.as_bytes().to_vec()));
        write_response_frame(&mut socket, FrameTag::Heartbeat, HELLO_ACK).unwrap();
        let (tag, request) = frame(&mut socket);
        assert_eq!(tag, FrameTag::TransferRequest);
        let text = String::from_utf8(request).unwrap();
        assert!(!text.contains("finalPath"), "local paths must stay on the sender");
        let meta: TransferMetadata = serde_json::from_str(&text).unwrap();
        assert_eq!(meta.total_files, 2);
        write_response_frame(&mut socket, FrameTag::TransferAccept, "ACCEPTED").unwrap();
        for expected in &meta.files {
            let (tag, header) = frame(&mut socket);
            assert_eq!(tag, FrameTag::FileHeader);
            let header: FileHeaderPayload = serde_json::from_slice(&header).unwrap();
            assert_eq!((header.file_index, header.resume_offset), (expected.file_index, 0));
            // Nothing may follow the header until it is acknowledged.
            // Non-blocking, not a receive timeout: a timed-out blocking read
            // leaves a Winsock socket in an indeterminate state.
            thread::sleep(Duration::from_millis(150));
            socket.set_nonblocking(true).unwrap();
            let early = socket.peek(&mut [0]);
            socket.set_nonblocking(false).unwrap();
            assert!(
                early.is_err_and(|error| error.kind() == std::io::ErrorKind::WouldBlock),
                "sender did not wait for the acknowledgement"
            );
            write_transfer_ack(&mut socket, &TransferAckPayload { status: "ACCEPTED".into(), resume_offset: 0 }).unwrap();
            let mut left = expected.size_bytes;
            while left > 0 {
                let (tag, data) = frame(&mut socket);
                assert_eq!(tag, FrameTag::DataChunk);
                left -= data.len() as u64;
            }
            let (tag, done) = frame(&mut socket);
            assert_eq!(tag, FrameTag::FileComplete);
            let done: FileCompletePayload = serde_json::from_slice(&done).unwrap();
            assert_eq!(done.bytes_written, expected.size_bytes);
            assert_eq!(done.sha256_checksum.len(), 64);
        }
        assert_eq!(frame(&mut socket), (FrameTag::TransferComplete, session_id.into_bytes()));
        write_response_frame(&mut socket, FrameTag::TransferAck, "ACK").unwrap();
    });
    let cancel = Arc::new(AtomicBool::new(false));
    let mut stream = CancellableStream::new(&mut sender_socket, cancel.clone()).unwrap();
    let summary = send_session(&mut stream, &job, &cancel, None, &mut |_| {}).unwrap();
    assert_eq!(summary.negotiated, Negotiated::default());
    peer.join().unwrap();
}

#[test]
fn thousands_of_files_fit_in_one_transfer() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let names: Vec<String> = (0..3000).map(|i| format!("Album {:02}/IMG_{i:05}.jpg", i % 40)).collect();
    let files: Vec<(&str, Vec<u8>)> =
        names.iter().enumerate().map(|(i, name)| (name.as_str(), bytes(1 + i % 23, i as u8))).collect();
    let job = sandbox.job(&files, true);
    assert!(serde_json::to_vec(&job.metadata()).unwrap().len() > MAX_METADATA_SIZE);
    let result = run(&job, &sandbox.dst(), Options::new(&registry));
    result.sent.unwrap();
    assert_eq!(result.received.unwrap().files.len(), 3000);
    sandbox.assert_received(&files);
}

#[test]
fn a_peer_that_predates_large_manifests_gets_a_clear_explanation() {
    let sandbox = Sandbox::new();
    // The second size is larger than the socket buffers, so the sender is
    // still writing the request when the peer goes away.
    for count in [3000usize, 40_000] {
        let files: Vec<SendFile> = (0..count)
            .map(|i| SendFile { path: sandbox.src().join("x"), relative_path: format!("Album/IMG_{i:06}.jpg"), size_bytes: 1 })
            .collect();
        let job = SendJob {
            session_id: "tx-large".into(),
            sender_id: "sender-device".into(),
            sender_name: "Sender".into(),
            sender_platform: "Test".into(),
            files,
            offer_features: true,
        };
        let (mut sender_socket, mut socket) = connect(Link::Direct);
        let peer = thread::spawn(move || {
            perform_incoming_handshake(&mut socket).unwrap();
            let mut header = [0u8; 10];
            socket.read_exact(&mut header).unwrap();
            assert_eq!(header[5], 0x0C); // an original peer fails here: unknown frame tag
            drop(socket);
        });
        let cancel = Arc::new(AtomicBool::new(false));
        let mut stream = CancellableStream::new(&mut sender_socket, cancel.clone()).unwrap();
        let error = send_session(&mut stream, &job, &cancel, None, &mut |_| {}).unwrap_err();
        assert!(error.contains("newer version"), "{count} files: {error}");
        peer.join().unwrap();
    }
}

#[test]
fn an_interrupted_transfer_continues_where_it_stopped() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let files = vec![
        ("a.bin", bytes(3 * 1024 * 1024, 1)),
        ("b.bin", bytes(2 * 1024 * 1024, 2)),
        ("c.bin", bytes(1024 * 1024, 3)),
    ];
    let job = sandbox.job(&files, true);

    let mut options = Options::new(&registry);
    options.link = Link::CutAfter(4 * 1024 * 1024);
    let first = run(&job, &sandbox.dst(), options);
    assert!(first.sent.is_err());
    let failure = first.received.unwrap_err();
    assert!(failure.resumable, "{}", failure.message);
    assert!(fs::read_dir(sandbox.dst()).unwrap().all(|e| e.unwrap().file_name() == ".dropflow-staging"),
        "nothing may be published before the whole transfer is verified");

    // A retry is a new session with the same files.
    let mut retry = sandbox.job(&files, true);
    retry.session_id = "tx-retry".into();
    let second = run(&retry, &sandbox.dst(), Options::new(&registry));
    let sent = second.sent.unwrap();
    let received = second.received.unwrap();
    assert!(sent.bytes_resumed >= 3 * 1024 * 1024, "resumed only {}", sent.bytes_resumed);
    assert_eq!(sent.bytes_resumed, received.bytes_resumed);
    assert_eq!(sent.bytes_sent + sent.bytes_resumed, retry.total_bytes());
    sandbox.assert_received(&files);
}

#[test]
fn resume_never_reuses_bytes_that_no_longer_match_the_source() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let mut files = vec![("a.bin", bytes(3 * 1024 * 1024, 1)), ("b.bin", bytes(1024 * 1024, 2))];
    let job = sandbox.job(&files, true);
    let mut options = Options::new(&registry);
    options.link = Link::CutAfter(2 * 1024 * 1024);
    assert!(run(&job, &sandbox.dst(), options).received.unwrap_err().resumable);

    // Same name and size, different content.
    files[0].1 = bytes(3 * 1024 * 1024, 77);
    let retry = sandbox.job(&files, true);
    let second = run(&retry, &sandbox.dst(), Options::new(&registry));
    let sent = second.sent.unwrap();
    second.received.unwrap();
    assert_eq!(sent.bytes_resumed, 0);
    assert_eq!(sent.bytes_sent, retry.total_bytes());
    sandbox.assert_received(&files);
}

#[test]
fn original_peers_never_leave_staged_data_behind() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let files = vec![("a.bin", bytes(3 * 1024 * 1024, 1))];
    let job = sandbox.job(&files, false);
    let mut options = Options::new(&registry);
    options.link = Link::CutAfter(1024 * 1024);
    let failure = run(&job, &sandbox.dst(), options).received.unwrap_err();
    assert!(!failure.resumable);
    assert_eq!(fs::read_dir(sandbox.dst()).unwrap().count(), 0);
}

#[test]
fn a_receiver_cancel_reaches_the_sender_and_leaves_nothing() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let job = sandbox.job(&[("a.bin", bytes(24 * 1024 * 1024, 1)), ("b.bin", bytes(1024, 2))], true);
    let mut options = Options::new(&registry);
    options.cancel_receiver_at = Some(1024 * 1024);
    let result = run(&job, &sandbox.dst(), options);
    assert_eq!(result.sent.unwrap_err(), "Transfer cancelled by recipient");
    let failure = result.received.unwrap_err();
    assert_eq!((failure.message.as_str(), failure.resumable), ("Transfer cancelled", false));
    assert_eq!(fs::read_dir(sandbox.dst()).unwrap().count(), 0);
}

#[test]
fn a_sender_cancel_reaches_the_receiver_and_leaves_nothing() {
    for offer in [true, false] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let job = sandbox.job(&[("a.bin", bytes(24 * 1024 * 1024, 1)), ("b.bin", bytes(1024, 2))], offer);
        let mut options = Options::new(&registry);
        options.cancel_sender_at = Some(1024 * 1024);
        let result = run(&job, &sandbox.dst(), options);
        assert_eq!(result.sent.unwrap_err(), "Transfer cancelled by user");
        let failure = result.received.unwrap_err();
        assert_eq!((failure.message.as_str(), failure.resumable), ("Transfer cancelled by sender", false), "offer={offer}");
        assert_eq!(fs::read_dir(sandbox.dst()).unwrap().count(), 0);
    }
}

#[test]
fn a_source_file_that_changes_mid_transfer_fails_cleanly_on_both_sides() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let mut job = sandbox.job(&[("a.bin", bytes(1024, 1)), ("b.bin", bytes(4096, 2))], true);
    job.files[1].size_bytes = 8192; // the file is shorter than announced
    let result = run(&job, &sandbox.dst(), Options::new(&registry));
    assert!(result.sent.unwrap_err().contains("shrank"));
    let failure = result.received.unwrap_err();
    assert!(failure.message.contains("stopped the transfer"), "{}", failure.message);
    assert!(!failure.resumable);
    assert_eq!(fs::read_dir(sandbox.dst()).unwrap().count(), 0);
}

#[test]
fn existing_files_are_never_overwritten() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    fs::write(sandbox.dst().join("a.txt"), b"mine").unwrap();
    let job = sandbox.job(&[("a.txt", b"theirs".to_vec())], true);
    let received = run(&job, &sandbox.dst(), Options::new(&registry)).received.unwrap();
    assert_eq!(fs::read(sandbox.dst().join("a.txt")).unwrap(), b"mine");
    assert_eq!(fs::read(&received.files[0].final_path).unwrap(), b"theirs");
    assert!(received.files[0].final_path.ends_with("a (1).txt"));
}

#[test]
fn a_cancel_after_verification_cannot_leave_a_transfer_half_saved() {
    for offer in [true, false] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let names: Vec<String> = (0..400).map(|i| format!("set/IMG_{i:04}.jpg")).collect();
        let files: Vec<(&str, Vec<u8>)> =
            names.iter().enumerate().map(|(i, name)| (name.as_str(), bytes(64, i as u8))).collect();
        let job = sandbox.job(&files, offer);
        let mut options = Options::new(&registry);
        options.cancel_receiver_while_saving = true;
        let result = run(&job, &sandbox.dst(), options);
        // Verified means delivered: every file is there and the sender is told so.
        assert_eq!(result.received.unwrap().files.len(), 400, "offer={offer}");
        result.sent.unwrap();
        sandbox.assert_received(&files);
    }
}

#[test]
fn a_name_that_is_both_a_file_and_a_folder_is_resolved_not_half_saved() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let files = vec![
        ("first.txt", b"1".to_vec()),
        ("notes", b"a file called notes".to_vec()),
        ("notes/inner.txt", b"inside the folder".to_vec()),
    ];
    // The sender's own validation would allow this manifest too.
    let job = sandbox.job(&[("first.txt", b"1".to_vec()), ("n", b"a file called notes".to_vec()), ("notes/inner.txt", b"inside the folder".to_vec())], true);
    let mut job = job;
    job.files[1].relative_path = "notes".into();
    let received = run(&job, &sandbox.dst(), Options::new(&registry)).received.unwrap();
    assert_eq!(fs::read(sandbox.dst().join("notes/inner.txt")).unwrap(), files[2].1);
    assert!(received.files[1].final_path.ends_with("notes (1)"));
    assert_eq!(fs::read(&received.files[1].final_path).unwrap(), files[1].1);
}

#[test]
fn a_destination_blocked_by_an_existing_file_fails_before_anything_is_saved() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    fs::write(sandbox.dst().join("Photos"), b"not a folder").unwrap();
    let job = sandbox.job(&[("a.txt", b"a".to_vec()), ("Photos/1.jpg", b"jpg".to_vec())], true);
    for _ in 0..2 {
        let result = run(&job, &sandbox.dst(), Options::new(&registry));
        assert!(result.sent.is_err());
        assert!(!result.received.unwrap_err().resumable);
        let left: Vec<_> = fs::read_dir(sandbox.dst()).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(left, ["Photos"], "a retry must not pile up copies");
    }
}

#[test]
fn look_alike_spellings_of_the_staging_folder_are_refused_where_they_alias_it() {
    // Plain case differences are already refused when the request is validated.
    for spelling in [".dropflow-\u{17f}taging", ".dropflow-\u{fb06}aging"] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let staging = sandbox.dst().join(".dropflow-staging");
        fs::create_dir_all(&staging).unwrap();
        let aliases = fs::metadata(sandbox.dst().join(spelling)).is_ok();
        let name = format!("{spelling}/k/0.part");
        let mut job = sandbox.job(&[("placeholder", b"data".to_vec())], true);
        job.files[0].relative_path = name.clone();
        let result = run(&job, &sandbox.dst(), Options::new(&registry));
        if aliases {
            // This filesystem treats the spelling as the staging folder itself.
            assert!(result.sent.is_err(), "{spelling} reached the staging folder");
            assert!(result.received.is_err());
        } else {
            // A genuinely different folder is an ordinary destination.
            result.sent.unwrap();
            assert_eq!(fs::read(sandbox.dst().join(&name)).unwrap(), b"data");
        }
        assert!(!staging.join("k").exists());
    }
}

#[test]
fn stale_staged_data_is_swept_and_fresh_data_is_kept() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let root = sandbox.dst().join(".dropflow-staging");
    for name in ["fresh", "abandoned"] {
        fs::create_dir_all(root.join(name)).unwrap();
        fs::write(root.join(name).join("0.part"), b"data").unwrap();
    }
    fs::write(root.join("fresh").join("kept"), b"").unwrap();
    sweep_staging(&sandbox.dst(), &registry, STAGING_MAX_AGE);
    assert!(root.join("fresh").exists());
    assert!(!root.join("abandoned").exists(), "data without a keep marker can never resume");
    sweep_staging(&sandbox.dst(), &registry, Duration::ZERO);
    assert!(!root.exists());
}

/// `cargo test --release --lib many_small_files_over_a_slow_link -- --ignored --nocapture`
/// Compares the original exchange with batch mode when every segment takes a
/// few milliseconds to cross, as on Wi-Fi. Loopback alone hides the per-file
/// round trip that batch mode removes.
#[test]
#[ignore = "timing benchmark; run explicitly"]
fn many_small_files_over_a_slow_link() {
    let names: Vec<String> = (0..500).map(|i| format!("photos/IMG_{i:04}.jpg")).collect();
    let files: Vec<(&str, Vec<u8>)> =
        names.iter().enumerate().map(|(i, name)| (name.as_str(), bytes(48 * 1024, i as u8))).collect();
    for delay_ms in [0u64, 2, 5] {
        let mut row = Vec::new();
        for offer in [false, true] {
            let mut trials = Vec::new();
            for _ in 0..3 {
                let sandbox = Sandbox::new();
                let registry = StagingRegistry::default();
                let job = sandbox.job(&files, offer);
                let mut options = Options::new(&registry);
                if delay_ms > 0 {
                    options.link = Link::Delay(Duration::from_millis(delay_ms));
                }
                let started = Instant::now();
                let result = run(&job, &sandbox.dst(), options);
                trials.push(started.elapsed());
                result.sent.unwrap();
                result.received.unwrap();
            }
            trials.sort();
            row.push(trials[1]);
        }
        println!(
            "{delay_ms} ms each way: original {:>7.0} ms  batch {:>6.0} ms  ({:.1}x)  [500 files x 48 KiB]",
            row[0].as_secs_f64() * 1000.0,
            row[1].as_secs_f64() * 1000.0,
            row[0].as_secs_f64() / row[1].as_secs_f64()
        );
    }
}

/// The shared network here drops after 256 KiB, far less than the files, so
/// the transfer can only succeed if the data really moved to the direct link.
#[test]
fn a_transfer_moves_onto_the_direct_link_in_every_arrangement() {
    // (sender rank, sender joins, receiver rank, receiver joins, host listens)
    for (s_rank, s_join, r_rank, r_join, host_listens) in [
        (0, true, 2, false, false), // computer receives and hosts; the phone listens
        (0, true, 2, false, true),  // same, but the host accepts the connection
        (2, false, 0, true, false), // computer sends and hosts
        (2, false, 0, true, true),
        (1, true, 2, true, false),  // both can host: the higher rank does
        (2, true, 1, true, true),
    ] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let files = vec![("a.bin", bytes(3 * 1024 * 1024, 1)), ("dir/b.bin", bytes(1024 * 1024, 2)), ("c.txt", b"c".to_vec())];
        let job = sandbox.job(&files, true);
        let mut options = Options::new(&registry);
        options.link = Link::CutAfter(256 * 1024);
        let mut sender = LoopbackLink::new(s_rank, s_join);
        let mut receiver = LoopbackLink::new(r_rank, r_join);
        sender.host_listens = host_listens;
        receiver.host_listens = host_listens;
        options.sender_link = Some(sender);
        options.receiver_link = Some(receiver);
        let case = format!("sender({s_rank},{s_join}) receiver({r_rank},{r_join}) host_listens={host_listens}");
        let result = run(&job, &sandbox.dst(), options);
        let sent = result.sent.unwrap_or_else(|e| panic!("{case}: {e}"));
        let received = result.received.unwrap_or_else(|e| panic!("{case}: {}", e.message));
        assert!(sent.direct && received.direct, "{case}");
        assert_eq!(sent.bytes_sent, job.total_bytes(), "{case}");
        sandbox.assert_received(&files);
    }
}

#[test]
fn a_link_that_cannot_be_set_up_leaves_the_transfer_on_the_shared_network() {
    for (fail_host, fail_join) in [(true, false), (false, true)] {
        for host_listens in [false, true] {
            let sandbox = Sandbox::new();
            let registry = StagingRegistry::default();
            let files = mixed_files();
            let job = sandbox.job(&files, true);
            let mut options = Options::new(&registry);
            let sender = LoopbackLink::new(0, true);
            let mut receiver = LoopbackLink::new(2, false);
            let (sender_closed, receiver_closed) = (sender.closed.clone(), receiver.closed.clone());
            let mut sender = sender;
            receiver.fail_host = fail_host;
            receiver.host_listens = host_listens;
            sender.fail_join = fail_join;
            options.sender_link = Some(sender);
            options.receiver_link = Some(receiver);
            let result = run(&job, &sandbox.dst(), options);
            let case = format!("fail_host={fail_host} fail_join={fail_join} host_listens={host_listens}");
            let sent = result.sent.unwrap_or_else(|e| panic!("{case}: {e}"));
            let received = result.received.unwrap_or_else(|e| panic!("{case}: {}", e.message));
            assert!(!sent.direct && !received.direct, "{case}");
            assert!(sender_closed.load(Ordering::Relaxed) && receiver_closed.load(Ordering::Relaxed), "{case}: link not released");
            sandbox.assert_received(&files);
        }
    }
}

#[test]
fn a_direct_link_is_only_used_when_both_devices_offer_a_workable_pairing() {
    // Only one side has a link; two joiners; two hosts that cannot join.
    for (sender, receiver) in [
        (Some((0, true)), None),
        (None, Some((2, false))),
        (Some((0, true)), Some((0, true))),
        (Some((2, false)), Some((2, false))),
    ] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let files = mixed_files();
        let job = sandbox.job(&files, true);
        let mut options = Options::new(&registry);
        options.sender_link = sender.map(|(rank, join)| LoopbackLink::new(rank, join));
        options.receiver_link = receiver.map(|(rank, join)| LoopbackLink::new(rank, join));
        let result = run(&job, &sandbox.dst(), options);
        assert!(!result.sent.unwrap().direct);
        assert!(!result.received.unwrap().direct);
        sandbox.assert_received(&files);
    }
}

#[test]
fn an_original_peer_is_never_asked_for_a_direct_link() {
    for (offer, allow) in [(false, true), (true, false)] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let files = mixed_files();
        let job = sandbox.job(&files, offer);
        let mut options = Options::new(&registry);
        options.allow_features = allow;
        options.sender_link = Some(LoopbackLink::new(0, true));
        options.receiver_link = Some(LoopbackLink::new(2, false));
        let result = run(&job, &sandbox.dst(), options);
        assert!(!result.sent.unwrap().direct);
        assert_eq!(result.received.unwrap().negotiated, Negotiated::default());
        sandbox.assert_received(&files);
    }
}

#[test]
fn cancelling_while_the_link_is_being_set_up_ends_the_transfer_cleanly() {
    struct Slow(LoopbackLink, Arc<AtomicBool>);
    impl DirectLink for Slow {
        fn caps(&self) -> DirectCaps { self.0.caps }
        fn host(&mut self, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<HostedLink, String> {
            // A radio that takes its time; the person cancels meanwhile.
            self.1.store(true, Ordering::Relaxed);
            loop {
                tick()?;
                thread::sleep(Duration::from_millis(20));
            }
        }
        fn join(&mut self, offer: &DirectOffer, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<JoinedLink, String> { self.0.join(offer, tick) }
        fn accept(&mut self, wait: Duration) -> Result<Option<TcpStream>, String> { self.0.accept(wait) }
        fn connect(&mut self, address: Option<&str>, port: u16) -> Result<TcpStream, String> { self.0.connect(address, port) }
        fn close(&mut self) { self.0.close() }
    }
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let job = sandbox.job(&[("a.bin", bytes(1024 * 1024, 1))], true);
    let (mut sender_socket, mut receiver_socket) = connect(Link::Direct);
    let dst = sandbox.dst();
    let hosting = Arc::new(AtomicBool::new(false));
    let receiver_cancel = Arc::new(AtomicBool::new(false));
    thread::scope(|scope| {
        let receiver = scope.spawn(|| {
            let mut stream = CancellableStream::new(&mut receiver_socket, receiver_cancel.clone()).unwrap();
            perform_incoming_handshake(&mut stream).unwrap();
            let meta = read_transfer_request(&mut stream).unwrap();
            let setup = ReceiveSetup { receive_dir: &dst, allow_features: true, staging: &registry };
            let mut link = Slow(LoopbackLink::new(2, false), hosting.clone());
            receive_session(&mut stream, &meta, &setup, &receiver_cancel, Some(&mut link), &mut |_| {})
        });
        let canceller = scope.spawn(|| {
            while !hosting.load(Ordering::Relaxed) {
                thread::sleep(Duration::from_millis(10));
            }
            thread::sleep(Duration::from_millis(100));
            receiver_cancel.store(true, Ordering::Relaxed);
        });
        let cancel = Arc::new(AtomicBool::new(false));
        let mut stream = CancellableStream::new(&mut sender_socket, cancel.clone()).unwrap();
        let mut link = LoopbackLink::new(0, true);
        let error = send_session(&mut stream, &job, &cancel, Some(&mut link), &mut |_| {}).unwrap_err();
        assert!(error.contains("cancelled"), "{error}");
        let failure = receiver.join().unwrap().unwrap_err();
        assert_eq!((failure.message.as_str(), failure.resumable), ("Transfer cancelled", false));
        canceller.join().unwrap();
    });
    assert_eq!(fs::read_dir(sandbox.dst()).unwrap().count(), 0);
}

/// A device with one Wi-Fi connection says where it will listen, leaves the
/// shared network, and the host finds it on the private one.
#[test]
fn a_device_that_must_leave_its_network_still_gets_a_direct_link() {
    for sender_leaves in [true, false] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let files = vec![("a.bin", bytes(3 * 1024 * 1024, 1)), ("b.txt", b"b".to_vec())];
        let job = sandbox.job(&files, true);
        let mut options = Options::new(&registry);
        options.link = Link::CutAfter(256 * 1024);
        let (mut joiner, host) = (LoopbackLink::new(0, true), LoopbackLink::new(2, false));
        joiner.leaves = true;
        if sender_leaves {
            (options.sender_link, options.receiver_link) = (Some(joiner), Some(host));
        } else {
            (options.sender_link, options.receiver_link) = (Some(host), Some(joiner));
        }
        let result = run(&job, &sandbox.dst(), options);
        let sent = result.sent.unwrap_or_else(|e| panic!("sender_leaves={sender_leaves}: {e}"));
        assert!(sent.direct && result.received.unwrap().direct, "sender_leaves={sender_leaves}");
        sandbox.assert_received(&files);
    }
}

#[test]
fn a_device_never_leaves_for_a_host_that_could_not_find_it() {
    for host_listens in [false, true] {
        let sandbox = Sandbox::new();
        let registry = StagingRegistry::default();
        let files = mixed_files();
        let job = sandbox.job(&files, true);
        let mut options = Options::new(&registry);
        let (mut joiner, mut host) = (LoopbackLink::new(0, true), LoopbackLink::new(2, false));
        joiner.leaves = true;
        host.finds_joiner = host_listens; // a listening host cannot take a leaver either
        host.host_listens = host_listens;
        (options.sender_link, options.receiver_link) = (Some(joiner), Some(host));
        let result = run(&job, &sandbox.dst(), options);
        // It stayed, so the transfer simply used the shared network.
        assert!(!result.sent.unwrap().direct, "host_listens={host_listens}");
        assert!(!result.received.unwrap().direct);
        sandbox.assert_received(&files);
    }
}

#[test]
fn once_a_device_has_left_a_failed_link_ends_the_transfer_and_keeps_what_arrived() {
    let sandbox = Sandbox::new();
    let registry = StagingRegistry::default();
    let job = sandbox.job(&[("a.bin", bytes(1024 * 1024, 1))], true);
    let mut options = Options::new(&registry);
    let (mut joiner, host) = (LoopbackLink::new(0, true), LoopbackLink::new(2, false));
    joiner.leaves = true;
    joiner.fail_join = true;
    let (joiner_closed, host_closed) = (joiner.closed.clone(), host.closed.clone());
    (options.sender_link, options.receiver_link) = (Some(joiner), Some(host));
    let result = run(&job, &sandbox.dst(), options);
    // No quiet fallback: there is no shared connection left to fall back to.
    assert!(result.sent.unwrap_err().contains("direct link could not be set up"));
    let failure = result.received.unwrap_err();
    assert!(failure.resumable, "{}", failure.message);
    assert!(joiner_closed.load(Ordering::Relaxed) && host_closed.load(Ordering::Relaxed), "links must be released so each device returns to its network");
}
