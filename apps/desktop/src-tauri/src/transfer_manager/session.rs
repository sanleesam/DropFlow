//! DFP/1 session logic for both roles. Independent of Tauri and of the UI, so
//! the same code runs in the app, in tests and in the command-line peer.
//!
//! Two peers that both offer the DFP/1.1 features use:
//! - `batch`: files stream back to back. There is no per-file round trip, and
//!   either side can report a cancel or an error while data is flowing.
//! - `resume`: the receiver keeps what it has when the connection drops, and
//!   the next attempt at the same files continues from there.
//!
//! Without them the original DFP/1 exchange is used, byte for byte.
use std::collections::{HashMap, HashSet, VecDeque};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime};

use sha2::{Digest, Sha256};

use super::bulk_io::{ChunkReader, ChunkWriter};
use super::protocol::{
    encode_frame_header, parse_frame_header, perform_incoming_handshake,
    perform_outgoing_handshake, read_frame_header, read_payload, write_frame,
    write_response_frame, write_transfer_ack, write_transfer_request, AcceptPayload, DirectCaps,
    DirectJoined, DirectOffer, FileCompletePayload, FileHeaderPayload, FileMetadata, FrameTag,
    ResumeEntry, TransferAckPayload, TransferMetadata, FRAME_HEADER_SIZE,
    MAX_LARGE_METADATA_SIZE,
};
use super::security::{publish_received_file, sanitize_relative_path, verify_safe_target_path};
use super::transport::CancellableStream;

pub const FEATURE_BATCH: &str = "batch";
pub const FEATURE_RESUME: &str = "resume";
pub const FEATURE_DIRECT: &str = "direct";

const STREAM_TIMEOUT: Duration = Duration::from_secs(15);
/// The recipient has 60 seconds to decide; do not time out before that.
const APPROVAL_TIMEOUT: Duration = Duration::from_secs(75);
const HEARTBEAT_EVERY: Duration = Duration::from_secs(2);
const PEER_CHECK_EVERY: Duration = Duration::from_millis(100);
const EMIT_EVERY: Duration = Duration::from_millis(50);
const SPEED_WINDOW: Duration = Duration::from_secs(3);
/// Control frames and small data frames are grouped into writes of this size.
const FLUSH_AT: usize = 64 * 1024;
const SMALL_FRAME: usize = 32 * 1024;
const MAX_RESUME_ENTRIES: usize = 100_000;
pub const STAGING_DIR: &str = ".dropflow-staging";
/// Interrupted data is kept this long for a retry, then removed.
pub const STAGING_MAX_AGE: Duration = Duration::from_secs(3 * 24 * 60 * 60);

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Negotiated {
    pub batch: bool,
    pub resume: bool,
}

pub struct Progress<'a> {
    pub file_index: u32,
    pub file_name: &'a str,
    pub file_bytes: u64,
    pub file_total: u64,
    /// Includes bytes that were already on the receiver from an earlier attempt.
    pub session_bytes: u64,
    pub session_total: u64,
    pub speed_bytes_per_sec: u64,
}

pub enum SessionEvent<'a> {
    /// Sender only: the request is out and the recipient is deciding.
    RequestSent,
    Accepted(Negotiated),
    /// The two devices are setting up a private link between them.
    Linking,
    /// The transfer moved onto the private link.
    Linked,
    Progress(Progress<'a>),
    /// Every byte has moved; the receiver is saving and confirming.
    Finishing,
}

// ─── Failures ────────────────────────────────────────────────────────────────

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FailKind {
    /// The connection failed. Staged data stays valid.
    Network,
    /// The other side cancelled, declined or reported an error.
    Peer,
    /// Something went wrong on this device.
    Local,
}

#[derive(Debug)]
struct Fail {
    kind: FailKind,
    message: String,
    /// What the other device is told. Never contains local paths.
    notice: &'static str,
}

impl Fail {
    fn network(error: impl std::fmt::Display) -> Self {
        Self { kind: FailKind::Network, message: format!("Connection lost: {error}"), notice: "" }
    }
    fn peer(message: impl Into<String>) -> Self {
        Self { kind: FailKind::Peer, message: message.into(), notice: "" }
    }
    fn local(message: impl Into<String>) -> Self {
        Self { kind: FailKind::Local, message: message.into(), notice: "it could not continue" }
    }
    fn protocol(message: impl Into<String>) -> Self {
        Self { kind: FailKind::Local, message: message.into(), notice: "the data did not match the transfer" }
    }
    fn disk(action: &str, error: io::Error) -> Self {
        // ENOSPC, ERROR_HANDLE_DISK_FULL, ERROR_DISK_FULL.
        let full = matches!(error.raw_os_error(), Some(28) | Some(39) | Some(112));
        Self {
            kind: FailKind::Local,
            message: if full {
                "Not enough storage space on this device".to_string()
            } else {
                format!("{action}: {error}")
            },
            notice: if full { "not enough storage space" } else { "it could not save the files" },
        }
    }
}

/// A failed receive. `resumable` means the staged data was kept for a retry.
#[derive(Debug)]
pub struct ReceiveFailure {
    pub message: String,
    pub resumable: bool,
}

// ─── Small helpers ───────────────────────────────────────────────────────────

fn hex(hasher: Sha256) -> String {
    format!("{:x}", hasher.finalize())
}

fn frame_bytes(tag: FrameTag, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(FRAME_HEADER_SIZE + payload.len());
    frame.extend_from_slice(&encode_frame_header(tag, payload.len() as u32));
    frame.extend_from_slice(payload);
    frame
}

fn check_cancel(cancel: &AtomicBool) -> Result<(), Fail> {
    if cancel.load(Ordering::Relaxed) {
        Err(Fail::local("Transfer cancelled by user"))
    } else {
        Ok(())
    }
}

fn read_header(stream: &mut CancellableStream<'_>) -> Result<(FrameTag, u32), Fail> {
    let mut header = [0u8; FRAME_HEADER_SIZE];
    stream.read_exact(&mut header).map_err(|error| {
        if error.kind() == io::ErrorKind::UnexpectedEof {
            Fail::network("the other device closed the connection")
        } else {
            Fail::network(error)
        }
    })?;
    parse_frame_header(&header).map_err(Fail::protocol)
}

fn read_body(stream: &mut CancellableStream<'_>, len: u32) -> Result<Vec<u8>, Fail> {
    read_payload(stream, len).map_err(Fail::network)
}

fn parse<'a, T: serde::Deserialize<'a>>(payload: &'a [u8], what: &str) -> Result<T, Fail> {
    serde_json::from_slice(payload).map_err(|e| Fail::protocol(format!("Invalid {what}: {e}")))
}

fn json<T: serde::Serialize>(value: &T) -> Result<Vec<u8>, Fail> {
    serde_json::to_vec(value).map_err(|e| Fail::local(format!("Failed to encode a frame: {e}")))
}

/// Hashes the first `len` bytes of `file`. `Ok(None)` means the bytes could
/// not be read, so the caller should not rely on them.
fn hash_prefix<E>(
    file: &mut File,
    len: u64,
    buffer: &mut [u8],
    mut tick: impl FnMut() -> Result<(), E>,
) -> Result<Option<Sha256>, E> {
    let mut hasher = Sha256::new();
    let mut left = len;
    while left > 0 {
        tick()?;
        let want = left.min(buffer.len() as u64) as usize;
        match file.read(&mut buffer[..want]) {
            Ok(0) | Err(_) => return Ok(None),
            Ok(count) => {
                hasher.update(&buffer[..count]);
                left -= count as u64;
            }
        }
    }
    Ok(Some(hasher))
}

/// Byte counter with a rate-limited, smoothed speed.
pub struct Meter {
    started: Instant,
    last_emit: Option<Instant>,
    samples: VecDeque<(Instant, u64)>,
    moved: u64,
}

impl Default for Meter {
    fn default() -> Self {
        Self::new()
    }
}

impl Meter {
    pub fn new() -> Self {
        Self { started: Instant::now(), last_emit: None, samples: VecDeque::new(), moved: 0 }
    }
    pub fn add(&mut self, bytes: u64) {
        self.moved += bytes;
    }
    pub fn moved(&self) -> u64 {
        self.moved
    }
    pub fn elapsed(&self) -> Duration {
        self.started.elapsed()
    }
    /// The current speed when an update is due (at most 20 a second) or forced.
    /// Speed covers the last few seconds, so it follows the link instead of
    /// averaging in everything since the start.
    pub fn due(&mut self, force: bool) -> Option<u64> {
        let now = Instant::now();
        if !force && self.last_emit.is_some_and(|last| now.duration_since(last) < EMIT_EVERY) {
            return None;
        }
        self.last_emit = Some(now);
        self.samples.push_back((now, self.moved));
        while self.samples.len() > 2 && now.duration_since(self.samples[0].0) > SPEED_WINDOW {
            self.samples.pop_front();
        }
        let (first_at, first_moved) = self.samples[0];
        let window = now.duration_since(first_at).as_secs_f64();
        let speed = if window >= 0.25 {
            (self.moved - first_moved) as f64 / window
        } else {
            let elapsed = now.duration_since(self.started).as_secs_f64();
            if elapsed > 0.0 { self.moved as f64 / elapsed } else { 0.0 }
        };
        Some(speed as u64)
    }
}

// ─── Sender ──────────────────────────────────────────────────────────────────

pub struct SendFile {
    pub path: PathBuf,
    pub relative_path: String,
    pub size_bytes: u64,
}

pub struct SendJob {
    pub session_id: String,
    pub sender_id: String,
    pub sender_name: String,
    pub sender_platform: String,
    pub files: Vec<SendFile>,
    /// Offer the DFP/1.1 features. Off reproduces the original exchange.
    pub offer_features: bool,
}

impl SendJob {
    pub fn total_bytes(&self) -> u64 {
        self.files.iter().map(|file| file.size_bytes).sum()
    }

    /// What goes on the wire: names and sizes, never local paths.
    pub fn metadata(&self) -> TransferMetadata {
        TransferMetadata {
            session_id: self.session_id.clone(),
            sender_id: self.sender_id.clone(),
            sender_name: self.sender_name.clone(),
            sender_platform: self.sender_platform.clone(),
            total_files: self.files.len() as u32,
            total_size_bytes: self.total_bytes(),
            files: self
                .files
                .iter()
                .enumerate()
                .map(|(index, file)| FileMetadata {
                    file_index: index as u32,
                    relative_path: file.relative_path.clone(),
                    size_bytes: file.size_bytes,
                    sha256_checksum: String::new(),
                    final_path: String::new(),
                })
                .collect(),
            features: if self.offer_features {
                vec![FEATURE_BATCH.to_string(), FEATURE_RESUME.to_string()]
            } else {
                Vec::new()
            },
            direct: None,
        }
    }
}

#[derive(Debug)]
pub struct SendSummary {
    pub negotiated: Negotiated,
    /// Bytes that crossed the network in this attempt.
    pub bytes_sent: u64,
    /// Bytes the receiver already had from an earlier attempt.
    pub bytes_resumed: u64,
    pub elapsed: Duration,
    /// The data travelled over a private link instead of the shared network.
    pub direct: bool,
}

/// Groups outgoing frames. In batch mode small frames share a write; in the
/// original mode every control frame is its own write, exactly as before.
struct Outbox {
    buffer: Vec<u8>,
    batch: bool,
    last_write: Instant,
}

impl Outbox {
    fn new(batch: bool) -> Self {
        Self { buffer: Vec::with_capacity(FLUSH_AT * 2), batch, last_write: Instant::now() }
    }
    fn control(
        &mut self,
        stream: &mut CancellableStream<'_>,
        tag: FrameTag,
        payload: &[u8],
    ) -> Result<(), Fail> {
        self.buffer.extend_from_slice(&encode_frame_header(tag, payload.len() as u32));
        self.buffer.extend_from_slice(payload);
        if !self.batch || self.buffer.len() >= FLUSH_AT {
            self.flush(stream)?;
        }
        Ok(())
    }
    fn data(&mut self, stream: &mut CancellableStream<'_>, frame: &[u8]) -> Result<(), Fail> {
        if self.batch && frame.len() < SMALL_FRAME {
            self.buffer.extend_from_slice(frame);
            if self.buffer.len() >= FLUSH_AT {
                self.flush(stream)?;
            }
            return Ok(());
        }
        self.flush(stream)?;
        stream.write_all(frame).map_err(Fail::network)?;
        self.last_write = Instant::now();
        Ok(())
    }
    fn flush(&mut self, stream: &mut CancellableStream<'_>) -> Result<(), Fail> {
        if !self.buffer.is_empty() {
            let result = stream.write_all(&self.buffer).map_err(Fail::network);
            self.buffer.clear();
            self.last_write = Instant::now();
            result?;
        }
        Ok(())
    }
    /// Keeps the receiver's read timeout from firing during long local work.
    fn keepalive(&mut self, stream: &mut CancellableStream<'_>) -> Result<(), Fail> {
        if self.batch && self.last_write.elapsed() >= HEARTBEAT_EVERY {
            self.control(stream, FrameTag::Heartbeat, b"")?;
            self.flush(stream)?;
        }
        Ok(())
    }
}

/// The next frame that is not a keep-alive.
fn read_reply(stream: &mut CancellableStream<'_>) -> Result<(FrameTag, Vec<u8>), Fail> {
    loop {
        let (tag, len) = read_header(stream)?;
        let payload = read_body(stream, len)?;
        // A late "the direct link failed" may still be on its way after both
        // sides have already settled on this connection.
        if !matches!(tag, FrameTag::Heartbeat | FrameTag::DirectResult) {
            return Ok((tag, payload));
        }
    }
}

fn notice_to_fail(tag: FrameTag, payload: &[u8]) -> Fail {
    match tag {
        FrameTag::Cancel => Fail::peer("Transfer cancelled by recipient"),
        FrameTag::Error => {
            let reason: String = String::from_utf8_lossy(payload).chars().take(200).collect();
            Fail::peer(format!("The other device stopped the transfer: {reason}"))
        }
        other => Fail::peer(format!("Unexpected {other:?} frame from the other device")),
    }
}

/// Non-blocking: has the receiver cancelled or failed while we were sending?
fn check_peer(stream: &mut CancellableStream<'_>) -> Result<(), Fail> {
    while stream.pending_input().map_err(Fail::network)? {
        let (tag, len) = read_header(stream)?;
        let payload = read_body(stream, len)?;
        if !matches!(tag, FrameTag::Heartbeat | FrameTag::DirectResult) {
            return Err(notice_to_fail(tag, &payload));
        }
    }
    Ok(())
}

pub fn send_session(
    stream: &mut CancellableStream<'_>,
    job: &SendJob,
    cancel: &AtomicBool,
    link: Option<&mut dyn DirectLink>,
    on_event: &mut dyn FnMut(SessionEvent<'_>),
) -> Result<SendSummary, String> {
    let mut negotiated = Negotiated::default();
    let caps = link.as_deref().map(|link| link.caps());
    let opened = match send_open(stream, job, cancel, caps, on_event, &mut negotiated) {
        Ok(opened) => opened,
        Err(fail) => return Err(send_failure(stream, fail, cancel, negotiated)),
    };
    let mut direct = None;
    if let (Some(i_host), Some(link)) = (opened.i_host, link) {
        on_event(SessionEvent::Linking);
        match link_phase(stream, link, i_host, cancel) {
            Ok(socket) => direct = socket,
            Err(fail) => return Err(send_failure(stream, fail, cancel, negotiated)),
        }
    }
    match direct.as_mut() {
        Some(socket) => {
            on_event(SessionEvent::Linked);
            let mut fast = CancellableStream::new(socket, stream.cancellation())
                .map_err(|e| format!("Failed to use the direct link: {e}"))?;
            send_stream(&mut fast, job, opened.resume, cancel, on_event, negotiated, true)
                .map_err(|fail| send_failure(&mut fast, fail, cancel, negotiated))
        }
        None => send_stream(stream, job, opened.resume, cancel, on_event, negotiated, false)
            .map_err(|fail| send_failure(stream, fail, cancel, negotiated)),
    }
}

/// Ends a failed send on whichever connection is in use: tells the receiver
/// why when that is possible, and picks up the receiver's own reason.
fn send_failure(
    stream: &mut CancellableStream<'_>,
    mut fail: Fail,
    cancel: &AtomicBool,
    negotiated: Negotiated,
) -> String {
    if cancel.load(Ordering::Relaxed) {
        // Wait for the receiver to read it: closing with its keep-alives
        // unread would reset the connection and could turn "cancelled" into
        // "connection lost" on the other side.
        stream.close_with_notice(&frame_bytes(FrameTag::Cancel, b""));
        return "Transfer cancelled by user".to_string();
    }
    if negotiated.batch {
        match fail.kind {
            // The receiver may have said why just before it closed.
            FailKind::Network => {
                let _ = stream.set_read_timeout(Some(Duration::from_secs(1)));
                if let Ok((tag, payload)) = read_reply(stream) {
                    if matches!(tag, FrameTag::Cancel | FrameTag::Error) {
                        fail = notice_to_fail(tag, &payload);
                    }
                }
            }
            FailKind::Local => {
                stream.close_with_notice(&frame_bytes(FrameTag::Error, b"it could not read a file"));
            }
            FailKind::Peer => {}
        }
    }
    fail.message
}

struct SendOpened {
    resume: HashMap<u32, ResumeEntry>,
    /// `Some(true)`: a direct link was agreed and this device creates it.
    i_host: Option<bool>,
}

/// Everything up to the receiver's answer: handshake, request, approval.
fn send_open(
    stream: &mut CancellableStream<'_>,
    job: &SendJob,
    cancel: &AtomicBool,
    caps: Option<DirectCaps>,
    on_event: &mut dyn FnMut(SessionEvent<'_>),
    negotiated: &mut Negotiated,
) -> Result<SendOpened, Fail> {
    if job.files.is_empty() {
        return Err(Fail::local("No files selected for transfer"));
    }
    check_cancel(cancel)?;
    let mut meta = job.metadata();
    let caps = caps.filter(|caps| job.offer_features && (caps.host_rank > 0 || caps.join));
    if let Some(caps) = caps {
        meta.features.push(FEATURE_DIRECT.to_string());
        meta.direct = Some(caps);
    }
    super::validation::validate_request(&meta).map_err(Fail::local)?;
    let timeouts = |stream: &mut CancellableStream<'_>, read: Duration| {
        stream
            .set_read_timeout(Some(read))
            .and_then(|_| stream.set_write_timeout(Some(STREAM_TIMEOUT)))
            .map_err(|e| Fail::local(format!("Failed to set stream timeout: {e}")))
    };
    timeouts(stream, STREAM_TIMEOUT)?;
    perform_outgoing_handshake(stream).map_err(Fail::network)?;

    // Do not apply the short streaming timeout while a person is deciding.
    timeouts(stream, APPROVAL_TIMEOUT)?;
    const NEEDS_UPDATE: &str =
        "The other device needs a newer version of DropFlow to receive this many files at once.";
    let large = write_transfer_request(stream, &meta).map_err(|error| {
        if error.starts_with("Too many files") {
            Fail::local(error)
        } else if error.contains("TransferRequestLarge") {
            // A peer that predates large manifests closes while we are still writing.
            Fail::peer(NEEDS_UPDATE)
        } else {
            Fail::network(error)
        }
    })?;
    let asked = Instant::now();
    on_event(SessionEvent::RequestSent);

    let (tag, payload) = read_reply(stream).map_err(|fail| {
        // A peer that predates large manifests drops the connection at once.
        if large && asked.elapsed() < Duration::from_secs(5) {
            Fail::peer(NEEDS_UPDATE)
        } else {
            fail
        }
    })?;
    match tag {
        FrameTag::TransferAccept => {}
        FrameTag::TransferReject => {
            let busy = String::from_utf8_lossy(&payload).to_lowercase().contains("busy");
            return Err(Fail::peer(if busy {
                "The other device is busy. Try again shortly."
            } else {
                "Transfer declined by recipient"
            }));
        }
        other => return Err(Fail::peer(format!("Peer rejected transfer with frame tag {other:?}"))),
    }
    let accept: AcceptPayload = serde_json::from_slice(&payload).unwrap_or_default();
    let granted = |name: &str| job.offer_features && accept.features.iter().any(|f| f == name);
    negotiated.batch = granted(FEATURE_BATCH);
    negotiated.resume = negotiated.batch && granted(FEATURE_RESUME);
    // The receiver names the host; accept only a role this device offered.
    let i_host = match (caps, accept.direct_host.as_deref()) {
        (Some(caps), Some("sender")) if caps.host_rank > 0 => Some(true),
        (Some(caps), Some("receiver")) if caps.join => Some(false),
        _ => None,
    }
    .filter(|_| negotiated.batch && granted(FEATURE_DIRECT));

    let mut resume: HashMap<u32, ResumeEntry> = HashMap::new();
    if negotiated.resume {
        let (tag, payload) = read_reply(stream)?;
        if tag != FrameTag::ResumeState {
            return Err(Fail::peer(format!("Expected ResumeState, received {tag:?}")));
        }
        let entries: Vec<ResumeEntry> = parse(&payload, "resume state")?;
        resume = entries.into_iter().map(|entry| (entry.file_index, entry)).collect();
    }
    on_event(SessionEvent::Accepted(*negotiated));
    timeouts(stream, STREAM_TIMEOUT)?;
    Ok(SendOpened { resume, i_host })
}

/// The files themselves, on whichever connection was chosen.
fn send_stream(
    stream: &mut CancellableStream<'_>,
    job: &SendJob,
    mut resume: HashMap<u32, ResumeEntry>,
    cancel: &AtomicBool,
    on_event: &mut dyn FnMut(SessionEvent<'_>),
    negotiated: Negotiated,
    direct: bool,
) -> Result<SendSummary, Fail> {
    stream
        .set_read_timeout(Some(STREAM_TIMEOUT))
        .and_then(|_| stream.set_write_timeout(Some(STREAM_TIMEOUT)))
        .map_err(|e| Fail::local(format!("Failed to set stream timeout: {e}")))?;
    let total = job.total_bytes();
    let mut meter = Meter::new();
    let mut out = Outbox::new(negotiated.batch);
    let mut chunks = ChunkWriter::new();
    let mut session_bytes = 0u64;
    let mut resumed = 0u64;
    let mut last_peer_check = Instant::now();
    let watch_peer = negotiated.batch;

    // Has the receiver cancelled or failed? Cheap, and at most ten times a second.
    let mut peer_check = move |stream: &mut CancellableStream<'_>| -> Result<(), Fail> {
        if watch_peer && last_peer_check.elapsed() >= PEER_CHECK_EVERY {
            last_peer_check = Instant::now();
            check_peer(stream)?;
        }
        Ok(())
    };

    for (index, file) in job.files.iter().enumerate() {
        check_cancel(cancel)?;
        peer_check(stream)?;
        let name = file.relative_path.as_str();
        let mut source = File::open(&file.path).map_err(|e| {
            Fail::local(format!("Failed to open file for reading {}: {e}", file.path.display()))
        })?;
        let mut hasher = Sha256::new();
        let mut offset = 0u64;

        // Continue only from bytes that are identical to ours.
        if let Some(entry) = resume.remove(&(index as u32)) {
            if entry.offset > 0 && entry.offset <= file.size_bytes {
                let ours = hash_prefix(&mut source, entry.offset, chunks.payload(), || {
                    check_cancel(cancel)?;
                    peer_check(stream)?;
                    out.keepalive(stream)
                })?;
                match ours {
                    Some(state) if hex(state.clone()).eq_ignore_ascii_case(&entry.sha256) => {
                        hasher = state;
                        offset = entry.offset;
                    }
                    _ => {
                        source
                            .seek(SeekFrom::Start(0))
                            .map_err(|e| Fail::local(format!("Failed to rewind file: {e}")))?;
                    }
                }
            }
        }

        out.control(
            stream,
            FrameTag::FileHeader,
            &json(&FileHeaderPayload {
                file_index: index as u32,
                relative_path: file.relative_path.clone(),
                size_bytes: file.size_bytes,
                sha256_checksum: String::new(),
                resume_offset: offset,
            })?,
        )?;

        if !negotiated.batch {
            let (tag, payload) = read_reply(stream)?;
            if tag != FrameTag::TransferAck && tag != FrameTag::TransferAccept {
                return Err(Fail::peer(format!("Expected TransferAck, received {tag:?}")));
            }
            let ack = serde_json::from_slice::<TransferAckPayload>(&payload).unwrap_or_else(|_| {
                TransferAckPayload { status: String::from_utf8_lossy(&payload).into_owned(), resume_offset: 0 }
            });
            if !ack.status.eq_ignore_ascii_case("ACCEPTED") {
                return Err(Fail::peer(format!("Receiver rejected file '{name}': {}", ack.status)));
            }
            if ack.resume_offset > 0 && ack.resume_offset < file.size_bytes {
                hasher = hash_prefix(&mut source, ack.resume_offset, chunks.payload(), || {
                    check_cancel(cancel)
                })?
                .ok_or_else(|| Fail::local("Unexpected EOF while hashing resume prefix"))?;
                offset = ack.resume_offset;
            }
        }

        let mut file_bytes = offset;
        session_bytes += offset;
        resumed += offset;

        while file_bytes < file.size_bytes {
            check_cancel(cancel)?;
            peer_check(stream)?;
            let buffer = chunks.payload();
            let limit = (file.size_bytes - file_bytes).min(buffer.len() as u64) as usize;
            let count = source
                .read(&mut buffer[..limit])
                .map_err(|e| Fail::local(format!("Failed reading file chunk: {e}")))?;
            if count == 0 {
                return Err(Fail::local(format!("'{name}' shrank after it was selected")));
            }
            hasher.update(&buffer[..count]);
            let frame = chunks.frame(count).map_err(|e| Fail::local(e.to_string()))?;
            out.data(stream, frame)?;

            file_bytes += count as u64;
            session_bytes += count as u64;
            meter.add(count as u64);
            if let Some(speed) = meter.due(session_bytes == total) {
                on_event(SessionEvent::Progress(Progress {
                    file_index: index as u32,
                    file_name: name,
                    file_bytes,
                    file_total: file.size_bytes,
                    session_bytes,
                    session_total: total,
                    speed_bytes_per_sec: speed,
                }));
            }
        }

        let grew = source
            .read(&mut [0])
            .map_err(|e| Fail::local(format!("Failed checking end of file: {e}")))?;
        if grew != 0 {
            return Err(Fail::local(format!("'{name}' grew after it was selected")));
        }
        out.control(
            stream,
            FrameTag::FileComplete,
            &json(&FileCompletePayload {
                file_index: index as u32,
                bytes_written: file_bytes,
                sha256_checksum: hex(hasher),
            })?,
        )?;
    }

    out.control(stream, FrameTag::TransferComplete, job.session_id.as_bytes())?;
    out.flush(stream)?;
    if let (Some(speed), Some(last)) = (meter.due(true), job.files.last()) {
        on_event(SessionEvent::Progress(Progress {
            file_index: job.files.len() as u32 - 1,
            file_name: &last.relative_path,
            file_bytes: last.size_bytes,
            file_total: last.size_bytes,
            session_bytes: total,
            session_total: total,
            speed_bytes_per_sec: speed,
        }));
    }
    on_event(SessionEvent::Finishing);

    // Complete only when the receiver confirms that every file is saved.
    let (tag, payload) = read_reply(stream)?;
    if tag != FrameTag::TransferAck {
        return Err(match tag {
            FrameTag::Cancel | FrameTag::Error => notice_to_fail(tag, &payload),
            other => Fail::peer(format!("Expected TransferAck, received {other:?}")),
        });
    }
    let status = serde_json::from_slice::<TransferAckPayload>(&payload)
        .map(|ack| ack.status)
        .unwrap_or_else(|_| String::from_utf8_lossy(&payload).into_owned());
    if !status.eq_ignore_ascii_case("ACK") {
        return Err(Fail::peer(if status.is_empty() {
            "Missing final acknowledgement"
        } else {
            "Invalid final acknowledgement"
        }));
    }
    let _ = stream.shutdown(std::net::Shutdown::Write);

    Ok(SendSummary {
        negotiated,
        bytes_sent: meter.moved(),
        bytes_resumed: resumed,
        elapsed: meter.elapsed(),
        direct,
    })
}

// ─── Direct link ─────────────────────────────────────────────────────────────
//
// Two devices on a slow shared network can agree to move the data onto a
// private Wi-Fi link between them. The existing connection carries only the
// arrangement; if the link does not come up, the transfer simply stays where
// it is. The platform part (creating or joining the network) is behind
// `DirectLink`, so this file never touches a radio.

/// How long the joiner waits for the host's offer.
const LINK_OFFER_TIMEOUT: Duration = Duration::from_secs(25);
/// How long the host waits for the joiner; a phone may ask its owner first.
const LINK_JOIN_TIMEOUT: Duration = Duration::from_secs(75);
/// How long a listening joiner waits for the host's connection.
const LINK_CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const LINK_GREETING_TIMEOUT: Duration = Duration::from_secs(5);
const LINK_POLL: Duration = Duration::from_millis(250);

/// The network a host created.
pub struct HostedLink {
    pub ssid: String,
    pub passphrase: String,
    /// Set when the host accepts the joiner's connection itself.
    pub listen_port: Option<u16>,
}

/// Where a joiner listens on the network it joined. Ignored when the host
/// listens instead.
pub struct JoinedLink {
    pub address: String,
    pub port: u16,
}

/// The platform side of a direct link. Every method may take seconds; the
/// long ones call `tick` at least once a second, and stop when it fails.
pub trait DirectLink {
    fn caps(&self) -> DirectCaps;
    /// Creates the private network.
    fn host(&mut self, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<HostedLink, String>;
    /// Joins the network in `offer`, without giving up the current one where
    /// the platform allows that.
    fn join(
        &mut self,
        offer: &DirectOffer,
        tick: &mut dyn FnMut() -> Result<(), String>,
    ) -> Result<JoinedLink, String>;
    /// Waits up to `wait` for the other device to connect on the private network.
    fn accept(&mut self, wait: Duration) -> Result<Option<TcpStream>, String>;
    /// Connects over the private network: to `address`, or to the network's
    /// gateway (the host) when it is `None`.
    fn connect(&mut self, address: Option<&str>, port: u16) -> Result<TcpStream, String>;
    /// Releases the network. Called once the session no longer needs it.
    fn close(&mut self);

    /// True when this device can hold only one Wi-Fi connection, so joining
    /// means leaving the shared network for the length of the transfer.
    fn leaves_network(&self) -> bool {
        false
    }
    /// For a device that leaves: start listening on every interface before
    /// it joins, and return the port.
    fn listen(&mut self) -> Result<u16, String> {
        Err("this device cannot listen before joining".into())
    }
    /// For a host: true when `connect_joined` works on this platform.
    fn accepts_leaving(&self) -> bool {
        false
    }
    /// For a host: wait for a device to join the private network and connect
    /// to it on `port`, at the address the platform itself reports for it.
    fn connect_joined(
        &mut self,
        _port: u16,
        _tick: &mut dyn FnMut() -> Result<(), String>,
    ) -> Result<TcpStream, String> {
        Err("this device cannot find a joiner by itself".into())
    }
}

/// Which side creates the network. The higher rank hosts; the other must be
/// able to join.
fn choose_host(sender: DirectCaps, receiver: DirectCaps) -> Option<&'static str> {
    let sender_can = sender.host_rank > 0 && receiver.join;
    let receiver_can = receiver.host_rank > 0 && sender.join;
    match (sender_can, receiver_can) {
        (true, true) if sender.host_rank > receiver.host_rank => Some("sender"),
        (_, true) => Some("receiver"),
        (true, false) => Some("sender"),
        (false, false) => None,
    }
}

/// Proves both ends of the new connection belong to this session.
fn greet(mut socket: TcpStream, token: &str, outgoing: bool) -> Result<TcpStream, String> {
    let timeouts = socket
        .set_read_timeout(Some(LINK_GREETING_TIMEOUT))
        .and_then(|_| socket.set_write_timeout(Some(LINK_GREETING_TIMEOUT)))
        .and_then(|_| socket.set_nodelay(true));
    timeouts.map_err(|e| e.to_string())?;
    let read = |socket: &mut TcpStream| -> Result<Vec<u8>, String> {
        let (tag, len) = read_frame_header(socket)?;
        if tag != FrameTag::DirectResult {
            return Err(format!("unexpected {tag:?} on the direct link"));
        }
        read_payload(socket, len)
    };
    if outgoing {
        perform_outgoing_handshake(&mut socket)?;
        write_frame(&mut socket, FrameTag::DirectResult, token.as_bytes())?;
        if read(&mut socket)? != b"OK" {
            return Err("the other device did not confirm the direct link".into());
        }
    } else {
        perform_incoming_handshake(&mut socket)?;
        if read(&mut socket)? != token.as_bytes() {
            return Err("an unknown device connected on the direct link".into());
        }
        write_frame(&mut socket, FrameTag::DirectResult, b"OK")?;
    }
    Ok(socket)
}

/// Tries to move the session onto a private link. `Ok(None)` means the
/// transfer stays on the current connection; only a failure of that
/// connection, or a cancel, is an error.
fn link_phase(
    stream: &mut CancellableStream<'_>,
    link: &mut dyn DirectLink,
    i_host: bool,
    cancel: &AtomicBool,
) -> Result<Option<TcpStream>, Fail> {
    let outcome = LinkSetup { stream, link, cancel, last_write: Instant::now() }.run(i_host);
    if !matches!(outcome, Ok(Some(_))) {
        link.close();
    }
    outcome
}

struct LinkSetup<'s, 'a> {
    stream: &'s mut CancellableStream<'a>,
    link: &'s mut dyn DirectLink,
    cancel: &'s AtomicBool,
    last_write: Instant,
}

impl LinkSetup<'_, '_> {
    fn run(&mut self, i_host: bool) -> Result<Option<TcpStream>, Fail> {
        self.stream
            .set_read_timeout(Some(STREAM_TIMEOUT))
            .map_err(|e| Fail::local(e.to_string()))?;
        if i_host {
            self.host()
        } else {
            self.join()
        }
    }

    /// Gives up on the link, tells the other side, and stays on this connection.
    fn stay(&mut self, reason: &str) -> Result<Option<TcpStream>, Fail> {
        check_cancel(self.cancel)?;
        log::info!("[Link] Staying on the shared network: {reason}");
        let text: String = format!("FAILED: {reason}").chars().take(200).collect();
        write_frame(self.stream, FrameTag::DirectResult, text.as_bytes()).map_err(Fail::network)?;
        Ok(None)
    }

    fn keep_alive(&mut self) -> Result<(), Fail> {
        check_cancel(self.cancel)?;
        if self.last_write.elapsed() >= HEARTBEAT_EVERY {
            write_frame(self.stream, FrameTag::Heartbeat, b"").map_err(Fail::network)?;
            self.last_write = Instant::now();
        }
        Ok(())
    }

    /// One frame from the other side on the existing connection, if any.
    fn pending(&mut self) -> Result<Option<(FrameTag, Vec<u8>)>, Fail> {
        if !self.stream.pending_input().map_err(Fail::network)? {
            return Ok(None);
        }
        self.next().map(Some)
    }

    fn next(&mut self) -> Result<(FrameTag, Vec<u8>), Fail> {
        let (tag, len) = read_header(self.stream)?;
        let payload = read_body(self.stream, len)?;
        if tag == FrameTag::Cancel {
            return Err(Fail::peer("Transfer cancelled by the other device"));
        }
        Ok((tag, payload))
    }

    /// Waits for the other device to connect on the private network, while
    /// watching the existing connection for "I gave up".
    fn await_connection(&mut self, token: &str, patience: Duration, late: &str) -> Result<Option<TcpStream>, Fail> {
        let deadline = Instant::now() + patience;
        loop {
            self.keep_alive()?;
            if Instant::now() >= deadline {
                return self.stay(late);
            }
            match self.pending()? {
                Some((FrameTag::DirectResult, _)) => return Ok(None),
                Some((FrameTag::Heartbeat, _)) | None => {}
                Some((tag, _)) => return self.stay(&format!("unexpected {tag:?} frame")),
            }
            match self.link.accept(LINK_POLL) {
                // A stranger on the link is ignored; the real peer may still arrive.
                Ok(Some(socket)) => {
                    if let Ok(socket) = greet(socket, token, false) {
                        return Ok(Some(socket));
                    }
                }
                Ok(None) => {}
                Err(reason) => return self.stay(&reason),
            }
        }
    }

    /// After one side has left the shared network there is nothing to fall
    /// back to: the transfer ends, and a retry continues it where resume applies.
    fn no_way_back(&self, reason: &str) -> Fail {
        if self.cancel.load(Ordering::Relaxed) {
            return Fail::local("Transfer cancelled by user");
        }
        Fail::network(format!("the direct link could not be set up ({reason})"))
    }

    /// The joiner's side when it can hold only one Wi-Fi connection.
    fn leave_and_join(&mut self, offer: &DirectOffer) -> Result<Option<TcpStream>, Fail> {
        if !offer.leaving_ok || offer.host_port.is_some() {
            return self.stay("this device would have to leave the network, and the other device cannot work with that");
        }
        let port = match self.link.listen() {
            Ok(port) => port,
            Err(reason) => return self.stay(&reason),
        };
        // The last frame on the shared connection.
        let leaving = DirectJoined { address: String::new(), port, leaving: true };
        write_frame(self.stream, FrameTag::DirectJoined, &json(&leaving)?).map_err(Fail::network)?;

        let cancel = self.cancel;
        if let Err(reason) = self.link.join(offer, &mut || cancel_tick(cancel)) {
            return Err(self.no_way_back(&reason));
        }
        let deadline = Instant::now() + LINK_CONNECT_TIMEOUT;
        loop {
            if Instant::now() >= deadline || self.cancel.load(Ordering::Relaxed) {
                return Err(self.no_way_back("the other device did not connect in time"));
            }
            match self.link.accept(LINK_POLL) {
                Ok(Some(socket)) => {
                    if let Ok(socket) = greet(socket, &offer.token, false) {
                        return Ok(Some(socket));
                    }
                }
                Ok(None) => {}
                Err(reason) => return Err(self.no_way_back(&reason)),
            }
        }
    }

    fn host(&mut self) -> Result<Option<TcpStream>, Fail> {
        let hosted = {
            let (stream, cancel, last_write) = (&mut *self.stream, self.cancel, &mut self.last_write);
            self.link.host(&mut || link_tick(stream, cancel, last_write))
        };
        let hosted = match hosted {
            Ok(hosted) => hosted,
            Err(reason) => return self.stay(&reason),
        };
        let token = uuid::Uuid::new_v4().simple().to_string();
        let offer = DirectOffer {
            ssid: hosted.ssid,
            passphrase: hosted.passphrase,
            token: token.clone(),
            host_port: hosted.listen_port,
            leaving_ok: hosted.listen_port.is_none() && self.link.accepts_leaving(),
        };
        write_frame(self.stream, FrameTag::DirectOffer, &json(&offer)?).map_err(Fail::network)?;
        self.last_write = Instant::now();

        if hosted.listen_port.is_some() {
            return self.await_connection(&token, LINK_JOIN_TIMEOUT, "the other device did not join in time");
        }
        // The joiner listens and says where; this side connects out.
        let deadline = Instant::now() + LINK_JOIN_TIMEOUT;
        loop {
            self.keep_alive()?;
            if Instant::now() >= deadline {
                return self.stay("the other device did not join in time");
            }
            let joined: DirectJoined = match self.next()? {
                (FrameTag::Heartbeat, _) => continue,
                (FrameTag::DirectResult, _) => return Ok(None),
                (FrameTag::DirectJoined, payload) => match serde_json::from_slice(&payload) {
                    Ok(joined) => joined,
                    Err(_) => return self.stay("the other device's address could not be read"),
                },
                (tag, _) => return self.stay(&format!("unexpected {tag:?} frame")),
            };
            if joined.leaving {
                // The other device has left the shared network to join ours:
                // this connection is finished, whatever happens next.
                let cancel = self.cancel;
                let connected = self
                    .link
                    .connect_joined(joined.port, &mut || cancel_tick(cancel))
                    .and_then(|socket| greet(socket, &token, true));
                return match connected {
                    Ok(socket) => Ok(Some(socket)),
                    Err(reason) => Err(self.no_way_back(&reason)),
                };
            }
            let connected = self
                .link
                .connect(Some(&joined.address), joined.port)
                .and_then(|socket| greet(socket, &token, true));
            return match connected {
                Ok(socket) => Ok(Some(socket)),
                Err(reason) => self.stay(&reason),
            };
        }
    }

    fn join(&mut self) -> Result<Option<TcpStream>, Fail> {
        let deadline = Instant::now() + LINK_OFFER_TIMEOUT;
        let offer: DirectOffer = loop {
            if Instant::now() >= deadline {
                return self.stay("the other device did not offer a link in time");
            }
            match self.next()? {
                (FrameTag::Heartbeat, _) => continue,
                (FrameTag::DirectResult, _) => return Ok(None),
                (FrameTag::DirectOffer, payload) => match serde_json::from_slice(&payload) {
                    Ok(offer) => break offer,
                    Err(_) => return self.stay("the link offer could not be read"),
                },
                (tag, _) => return self.stay(&format!("unexpected {tag:?} frame")),
            }
        };
        if self.link.leaves_network() {
            return self.leave_and_join(&offer);
        }
        let joined = {
            let (stream, cancel, last_write) = (&mut *self.stream, self.cancel, &mut self.last_write);
            self.link.join(&offer, &mut || link_tick(stream, cancel, last_write))
        };
        let joined = match joined {
            Ok(joined) => joined,
            Err(reason) => return self.stay(&reason),
        };
        if let Some(port) = offer.host_port {
            let connected =
                self.link.connect(None, port).and_then(|socket| greet(socket, &offer.token, true));
            return match connected {
                Ok(socket) => Ok(Some(socket)),
                Err(reason) => self.stay(&reason),
            };
        }
        let here = DirectJoined { address: joined.address, port: joined.port, leaving: false };
        write_frame(self.stream, FrameTag::DirectJoined, &json(&here)?).map_err(Fail::network)?;
        self.last_write = Instant::now();
        self.await_connection(&offer.token, LINK_CONNECT_TIMEOUT, "the other device did not connect in time")
    }
}

/// For work that happens after the shared connection is gone: only a cancel
/// can stop it.
fn cancel_tick(cancel: &AtomicBool) -> Result<(), String> {
    if cancel.load(Ordering::Relaxed) {
        Err("cancelled".into())
    } else {
        Ok(())
    }
}

/// Keeps the existing connection alive while the platform works on the link.
fn link_tick(
    stream: &mut CancellableStream<'_>,
    cancel: &AtomicBool,
    last_write: &mut Instant,
) -> Result<(), String> {
    if cancel.load(Ordering::Relaxed) {
        return Err("cancelled".into());
    }
    if last_write.elapsed() >= HEARTBEAT_EVERY {
        write_frame(stream, FrameTag::Heartbeat, b"")?;
        *last_write = Instant::now();
    }
    Ok(())
}

// ─── Receiver staging ────────────────────────────────────────────────────────

/// Staging folders in use by this process. Two sessions never share one.
#[derive(Default)]
pub struct StagingRegistry {
    active: Mutex<HashSet<PathBuf>>,
}

impl StagingRegistry {
    fn claim(&self, dir: &Path) -> bool {
        self.active.lock().map(|mut active| active.insert(dir.to_path_buf())).unwrap_or(false)
    }
    fn release(&self, dir: &Path) {
        if let Ok(mut active) = self.active.lock() {
            active.remove(dir);
        }
    }
    fn is_active(&self, dir: &Path) -> bool {
        self.active.lock().map(|active| active.contains(dir)).unwrap_or(true)
    }
}

/// Removes staged data that is too old to resume, and leftovers from sessions
/// that could never resume. Safe to call at any time.
pub fn sweep_staging(receive_dir: &Path, registry: &StagingRegistry, max_age: Duration) {
    let root = receive_dir.join(STAGING_DIR);
    let Ok(entries) = fs::read_dir(&root) else { return };
    let now = SystemTime::now();
    for entry in entries.flatten() {
        let dir = entry.path();
        if registry.is_active(&dir) {
            continue;
        }
        let kept_at = fs::metadata(dir.join("kept")).and_then(|meta| meta.modified()).ok();
        let fresh = kept_at
            .is_some_and(|at| now.duration_since(at).map_or(true, |age| age < max_age));
        if !fresh {
            let _ = fs::remove_dir_all(&dir);
        }
    }
    let _ = fs::remove_dir(&root); // only succeeds when empty
}

/// A dot-prefixed name hides the folder on macOS and Linux only. On Windows
/// it needs the hidden attribute; failing to set it just leaves it visible.
#[cfg(windows)]
fn hide_folder(path: &Path) {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    extern "system" {
        fn SetFileAttributesW(path: *const u16, attributes: u32) -> i32;
    }
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    // FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_NOT_CONTENT_INDEXED. NUL terminated.
    unsafe { SetFileAttributesW(wide.as_ptr(), 0x2 | 0x2000) };
}
#[cfg(not(windows))]
fn hide_folder(_path: &Path) {}

struct Staging<'a> {
    dir: PathBuf,
    root: PathBuf,
    registry: &'a StagingRegistry,
}

impl Drop for Staging<'_> {
    fn drop(&mut self) {
        self.registry.release(&self.dir);
    }
}

impl<'a> Staging<'a> {
    /// The same sender offering the same names and sizes maps to the same
    /// folder, which is what lets a retry continue. Anything else is unique.
    fn open(
        receive_dir: &Path,
        registry: &'a StagingRegistry,
        meta: &TransferMetadata,
        negotiated: &mut Negotiated,
    ) -> Result<Self, Fail> {
        let root = receive_dir.join(STAGING_DIR);
        let real_dir = |path: &Path| -> Result<(), Fail> {
            match fs::symlink_metadata(path) {
                Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => Ok(()),
                Ok(_) => Err(Fail::local("The staging folder is not a regular folder")),
                Err(_) => fs::create_dir_all(path)
                    .map_err(|e| Fail::disk("Failed to create the staging folder", e)),
            }
        };
        real_dir(&root)?;
        hide_folder(&root);
        let unique = || root.join(format!("s-{}", uuid::Uuid::new_v4().simple()));
        let mut dir = unique();
        if negotiated.resume {
            let mut key = Sha256::new();
            key.update(meta.sender_id.as_bytes());
            for file in &meta.files {
                key.update([0]);
                key.update(file.relative_path.as_bytes());
                key.update([0]);
                key.update(file.size_bytes.to_be_bytes());
            }
            let shared = root.join(&hex(key)[..32]);
            if registry.claim(&shared) {
                dir = shared;
            } else {
                // The same transfer is already running; do not share its files.
                negotiated.resume = false;
            }
        }
        if !negotiated.resume && !registry.claim(&dir) {
            return Err(Fail::local("Could not reserve a staging folder"));
        }
        let staging = Self { dir, root, registry };
        real_dir(&staging.dir)?;
        let _ = fs::remove_file(staging.dir.join("kept"));
        Ok(staging)
    }

    fn part(&self, index: u32) -> PathBuf {
        self.dir.join(format!("{index}.part"))
    }

    /// True when `folder` really is the staging root or lies inside it,
    /// however its path was spelled.
    fn contains(&self, folder: &Path) -> bool {
        match (folder.canonicalize(), self.root.canonicalize()) {
            (Ok(folder), Ok(root)) => folder.starts_with(root),
            _ => true, // cannot tell: refuse
        }
    }

    fn discard(&self) {
        let _ = fs::remove_dir_all(&self.dir);
        let _ = fs::remove_dir(&self.root);
    }

    /// Marks the data as worth keeping; `sweep_staging` ages it from now.
    fn keep(&self) {
        let _ = fs::write(self.dir.join("kept"), b"");
    }

    /// What is already here, with the hash state of each held prefix.
    fn scan(
        &self,
        meta: &TransferMetadata,
        buffer: &mut [u8],
        mut tick: impl FnMut() -> Result<(), Fail>,
    ) -> Result<HashMap<u32, (u64, Sha256)>, Fail> {
        let mut found = HashMap::new();
        let Ok(entries) = fs::read_dir(&self.dir) else { return Ok(found) };
        for entry in entries.flatten() {
            let path = entry.path();
            let index = entry
                .file_name()
                .to_str()
                .and_then(|name| name.strip_suffix(".part"))
                .and_then(|number| number.parse::<u32>().ok());
            let expected = index.and_then(|index| meta.files.get(index as usize));
            let held = fs::symlink_metadata(&path)
                .ok()
                .filter(|meta| meta.is_file())
                .map(|meta| meta.len());
            let (Some(index), Some(expected), Some(held)) = (index, expected, held) else {
                let _ = fs::remove_file(&path);
                continue;
            };
            if held == 0 || held > expected.size_bytes || found.len() >= MAX_RESUME_ENTRIES {
                let _ = fs::remove_file(&path);
                continue;
            }
            let state = match File::open(&path) {
                Ok(mut file) => hash_prefix(&mut file, held, buffer, &mut tick)?,
                Err(_) => None,
            };
            match state {
                Some(state) => {
                    found.insert(index, (held, state));
                }
                None => {
                    let _ = fs::remove_file(&path);
                }
            }
        }
        Ok(found)
    }
}

// ─── Receiver ────────────────────────────────────────────────────────────────

pub struct ReceiveSetup<'a> {
    pub receive_dir: &'a Path,
    /// Accept the DFP/1.1 features when the sender offers them.
    pub allow_features: bool,
    pub staging: &'a StagingRegistry,
}

#[derive(Debug)]
pub struct ReceiveSummary {
    pub negotiated: Negotiated,
    /// Every received file, with the path it was saved to.
    pub files: Vec<FileMetadata>,
    pub bytes_received: u64,
    pub bytes_resumed: u64,
    pub elapsed: Duration,
    /// The data travelled over a private link instead of the shared network.
    pub direct: bool,
}

/// Runs an approved transfer: sends the acceptance, receives and verifies
/// every file, saves them, then confirms. The caller has already read and
/// validated the request and obtained the person's approval.
pub fn receive_session(
    stream: &mut CancellableStream<'_>,
    meta: &TransferMetadata,
    setup: &ReceiveSetup<'_>,
    cancel: &AtomicBool,
    mut link: Option<&mut dyn DirectLink>,
    on_event: &mut dyn FnMut(SessionEvent<'_>),
) -> Result<ReceiveSummary, ReceiveFailure> {
    let offered = |name: &str| meta.features.iter().any(|feature| feature == name);
    let mut negotiated = Negotiated {
        batch: setup.allow_features && offered(FEATURE_BATCH),
        resume: false,
    };
    negotiated.resume = negotiated.batch && offered(FEATURE_RESUME);

    sweep_staging(setup.receive_dir, setup.staging, STAGING_MAX_AGE);
    let stage = match Staging::open(setup.receive_dir, setup.staging, meta, &mut negotiated) {
        Ok(stage) => stage,
        Err(fail) => {
            let _ = write_response_frame(stream, FrameTag::TransferReject, "REJECTED");
            return Err(ReceiveFailure { message: fail.message, resumable: false });
        }
    };

    // A direct link needs both devices to offer it and a workable pairing.
    let host = match (link.as_deref().map(|link| link.caps()), meta.direct) {
        (Some(mine), Some(theirs)) if negotiated.batch && offered(FEATURE_DIRECT) => {
            choose_host(theirs, mine)
        }
        _ => None,
    };

    let result = (|| {
        let staged = receive_open(stream, meta, &stage, negotiated, host, cancel, on_event)
            .map_err(|fail| receive_failure(stream, fail, cancel, negotiated))?;
        let mut direct = None;
        if let (Some(host), Some(link)) = (host, link.as_deref_mut()) {
            on_event(SessionEvent::Linking);
            direct = link_phase(stream, link, host == "receiver", cancel)
                .map_err(|fail| receive_failure(stream, fail, cancel, negotiated))?;
        }
        match direct.as_mut() {
            Some(socket) => {
                on_event(SessionEvent::Linked);
                let mut fast = CancellableStream::new(socket, stream.cancellation())
                    .map_err(|e| (Fail::local(format!("Failed to use the direct link: {e}")), false))?;
                receive_stream(&mut fast, meta, setup.receive_dir, &stage, negotiated, staged, cancel, on_event, true)
                    .map_err(|fail| receive_failure(&mut fast, fail, cancel, negotiated))
            }
            None => receive_stream(stream, meta, setup.receive_dir, &stage, negotiated, staged, cancel, on_event, false)
                .map_err(|fail| receive_failure(stream, fail, cancel, negotiated)),
        }
    })();

    match result {
        Ok(summary) => {
            stage.discard();
            Ok(summary)
        }
        Err((fail, cancelled)) => {
            let resumable = negotiated.resume && fail.kind == FailKind::Network && !cancelled;
            if resumable {
                stage.keep();
            } else {
                stage.discard();
            }
            Err(ReceiveFailure {
                message: if cancelled { "Transfer cancelled".to_string() } else { fail.message },
                resumable,
            })
        }
    }
}

/// Ends a failed receive on whichever connection is in use, telling the
/// sender why when that is possible. Returns the failure and whether this
/// device cancelled.
fn receive_failure(
    stream: &mut CancellableStream<'_>,
    fail: Fail,
    cancel: &AtomicBool,
    negotiated: Negotiated,
) -> (Fail, bool) {
    let cancelled = cancel.load(Ordering::Relaxed);
    if negotiated.batch {
        if cancelled {
            stream.close_with_notice(&frame_bytes(FrameTag::Cancel, b""));
        } else if fail.kind == FailKind::Local {
            stream.close_with_notice(&frame_bytes(FrameTag::Error, fail.notice.as_bytes()));
        }
    }
    (fail, cancelled)
}

/// The next frame of the stream. Handles the frames that may arrive at any
/// point: a cancel, and (in batch mode) keep-alives and the sender's errors.
fn next_frame(stream: &mut CancellableStream<'_>, batch: bool) -> Result<(FrameTag, u32), Fail> {
    loop {
        let (tag, len) = read_header(stream)?;
        match tag {
            FrameTag::Cancel => {
                let _ = read_body(stream, len);
                return Err(Fail::peer("Transfer cancelled by sender"));
            }
            FrameTag::Heartbeat | FrameTag::DirectResult if batch => {
                read_body(stream, len)?;
            }
            FrameTag::Error if batch => {
                let payload = read_body(stream, len)?;
                let reason: String = String::from_utf8_lossy(&payload).chars().take(200).collect();
                return Err(Fail::peer(format!("The sending device stopped the transfer: {reason}")));
            }
            _ => return Ok((tag, len)),
        }
    }
}

fn expect(tag: FrameTag, wanted: FrameTag) -> Result<(), Fail> {
    if tag == wanted {
        Ok(())
    } else {
        Err(Fail::protocol(format!("Expected {wanted:?} frame tag, received {tag:?}")))
    }
}

/// Checks what is already staged and sends the acceptance.
fn receive_open(
    stream: &mut CancellableStream<'_>,
    meta: &TransferMetadata,
    stage: &Staging<'_>,
    negotiated: Negotiated,
    direct_host: Option<&'static str>,
    cancel: &AtomicBool,
    on_event: &mut dyn FnMut(SessionEvent<'_>),
) -> Result<HashMap<u32, (u64, Sha256)>, Fail> {
    let timeouts = stream
        .set_read_timeout(Some(STREAM_TIMEOUT))
        .and_then(|_| stream.set_write_timeout(Some(STREAM_TIMEOUT)));
    timeouts.map_err(|e| Fail::local(format!("Failed to set stream timeout: {e}")))?;

    let mut scratch = vec![0u8; 256 * 1024];
    let mut last_write = Instant::now();
    // Long local work (hashing staged data, saving many files) must not look
    // like a dead connection to the sender.
    let mut keepalive = |stream: &mut CancellableStream<'_>| -> Result<(), Fail> {
        check_cancel(cancel)?;
        if negotiated.batch && last_write.elapsed() >= HEARTBEAT_EVERY {
            write_frame(stream, FrameTag::Heartbeat, b"").map_err(Fail::network)?;
            last_write = Instant::now();
        }
        Ok(())
    };

    let staged = if negotiated.resume {
        stage.scan(meta, &mut scratch, || keepalive(stream))?
    } else {
        HashMap::new()
    };

    if negotiated.batch {
        let mut features = vec![FEATURE_BATCH.to_string()];
        if negotiated.resume {
            features.push(FEATURE_RESUME.to_string());
        }
        if direct_host.is_some() {
            features.push(FEATURE_DIRECT.to_string());
        }
        let accept = AcceptPayload {
            status: "ACCEPTED".to_string(),
            features,
            direct_host: direct_host.map(str::to_string),
        };
        write_frame(stream, FrameTag::TransferAccept, &json(&accept)?).map_err(Fail::network)?;
    } else {
        write_response_frame(stream, FrameTag::TransferAccept, "ACCEPTED").map_err(Fail::network)?;
    }
    if negotiated.resume {
        let mut entries: Vec<ResumeEntry> = staged
            .iter()
            .map(|(index, (held, state))| ResumeEntry {
                file_index: *index,
                offset: *held,
                sha256: hex(state.clone()),
            })
            .collect();
        entries.sort_by_key(|entry| entry.file_index);
        let payload = json(&entries)?;
        if payload.len() > MAX_LARGE_METADATA_SIZE {
            return Err(Fail::local("Resume state is too large"));
        }
        write_frame(stream, FrameTag::ResumeState, &payload).map_err(Fail::network)?;
    }
    on_event(SessionEvent::Accepted(negotiated));
    Ok(staged)
}

/// Receives, verifies and saves the files, on whichever connection was chosen.
#[allow(clippy::too_many_arguments)]
fn receive_stream(
    stream: &mut CancellableStream<'_>,
    meta: &TransferMetadata,
    receive_dir: &Path,
    stage: &Staging<'_>,
    negotiated: Negotiated,
    mut staged: HashMap<u32, (u64, Sha256)>,
    cancel: &AtomicBool,
    on_event: &mut dyn FnMut(SessionEvent<'_>),
    direct: bool,
) -> Result<ReceiveSummary, Fail> {
    stream
        .set_read_timeout(Some(STREAM_TIMEOUT))
        .and_then(|_| stream.set_write_timeout(Some(STREAM_TIMEOUT)))
        .map_err(|e| Fail::local(format!("Failed to set stream timeout: {e}")))?;
    let mut last_write = Instant::now();
    let total = meta.total_size_bytes;
    let mut meter = Meter::new();
    let mut chunks = ChunkReader::default();
    let mut session_bytes = 0u64;
    let mut resumed = 0u64;
    let mut received: Vec<FileMetadata> = Vec::with_capacity(meta.files.len());
    let mut targets: Vec<PathBuf> = Vec::with_capacity(meta.files.len());

    // A batch sender can have written a whole folder of small files into the
    // socket buffers and already be waiting for the final reply while this
    // device is still creating files. Slow storage must not look like a dead
    // connection, so keep-alives continue while receiving.
    macro_rules! keep_alive {
        () => {
            if negotiated.batch && last_write.elapsed() >= HEARTBEAT_EVERY {
                write_frame(stream, FrameTag::Heartbeat, b"").map_err(Fail::network)?;
                last_write = Instant::now();
            }
        };
    }

    for (index, expected) in meta.files.iter().enumerate() {
        check_cancel(cancel)?;
        keep_alive!();
        let (tag, len) = next_frame(stream, negotiated.batch)?;
        expect(tag, FrameTag::FileHeader)?;
        let header: FileHeaderPayload = parse(&read_body(stream, len)?, "file header")?;
        super::validation::validate_header(meta, index, &header).map_err(Fail::protocol)?;
        let name = expected.relative_path.as_str();
        let size = expected.size_bytes;

        let relative = sanitize_relative_path(&header.relative_path).map_err(Fail::protocol)?;
        let target = verify_safe_target_path(receive_dir, &relative).map_err(Fail::protocol)?;
        // Make the destination folder now, so a name conflict stops the
        // transfer before anything is saved rather than half-way through saving.
        if let Some(parent) = target.parent() {
            // Compare real locations, not spellings: filesystems fold case and
            // some Unicode, and Windows has short names. Checked on the part
            // of the path that already exists, before anything is created.
            let mut existing = parent;
            while fs::symlink_metadata(existing).is_err() {
                match existing.parent() {
                    Some(up) => existing = up,
                    None => break,
                }
            }
            if stage.contains(existing) {
                return Err(Fail::protocol("A file path uses a reserved folder name"));
            }
            fs::create_dir_all(parent)
                .map_err(|e| Fail::disk("Failed to create the destination folder", e))?;
        }
        let part = stage.part(index as u32);

        let offer = staged.remove(&(index as u32));
        let (mut file, mut hasher, start) = match (header.resume_offset, offer) {
            (0, _) => {
                let file = File::create(&part)
                    .map_err(|e| Fail::disk("Failed to create part file", e))?;
                (file, Sha256::new(), 0)
            }
            (offset, Some((held, state))) if negotiated.resume && offset == held => {
                let mut file = OpenOptions::new()
                    .write(true)
                    .open(&part)
                    .map_err(|e| Fail::disk("Failed to reopen part file", e))?;
                file.set_len(offset)
                    .and_then(|_| file.seek(SeekFrom::End(0)))
                    .map_err(|e| Fail::disk("Failed to continue part file", e))?;
                (file, state, offset)
            }
            _ => return Err(Fail::protocol("File header does not match the resume offer")),
        };

        if !negotiated.batch {
            write_transfer_ack(
                stream,
                &TransferAckPayload { status: "ACCEPTED".to_string(), resume_offset: 0 },
            )
            .map_err(Fail::network)?;
        }

        let mut file_bytes = start;
        session_bytes += start;
        resumed += start;

        while file_bytes < size {
            let (tag, len) = next_frame(stream, negotiated.batch)?;
            expect(tag, FrameTag::DataChunk)?;
            let chunk = chunks.read(stream, len as usize, size - file_bytes).map_err(|error| {
                if error.kind() == io::ErrorKind::InvalidData {
                    Fail::protocol(format!("Invalid data frame for '{name}'"))
                } else {
                    Fail::network(error)
                }
            })?;
            file.write_all(chunk).map_err(|e| Fail::disk("Failed to write chunk to disk", e))?;
            hasher.update(chunk);

            file_bytes += len as u64;
            session_bytes += len as u64;
            meter.add(len as u64);
            keep_alive!();
            if let Some(speed) = meter.due(session_bytes == total) {
                on_event(SessionEvent::Progress(Progress {
                    file_index: index as u32,
                    file_name: name,
                    file_bytes,
                    file_total: size,
                    session_bytes,
                    session_total: total,
                    speed_bytes_per_sec: speed,
                }));
            }
        }
        file.flush().map_err(|e| Fail::disk("Failed to flush file to disk", e))?;
        drop(file);

        let (tag, len) = next_frame(stream, negotiated.batch)?;
        expect(tag, FrameTag::FileComplete)?;
        let complete: FileCompletePayload = parse(&read_body(stream, len)?, "file completion")?;
        if complete.file_index != header.file_index || complete.bytes_written != file_bytes {
            return Err(Fail::protocol(format!("Invalid FileComplete payload for '{name}'")));
        }
        let digest = hex(hasher);
        let claimed = complete.sha256_checksum.trim();
        let mismatch = if claimed.is_empty() {
            // The original protocol allowed a missing digest; 1.1 does not.
            negotiated.batch
        } else {
            !claimed.eq_ignore_ascii_case(&digest)
        };
        if mismatch {
            let _ = fs::remove_file(&part);
            return Err(Fail::protocol(format!("File checksum mismatch for file {name}")));
        }

        received.push(FileMetadata {
            file_index: header.file_index,
            relative_path: header.relative_path.clone(),
            size_bytes: size,
            sha256_checksum: digest,
            final_path: target.to_string_lossy().into_owned(),
        });
        targets.push(target);
    }

    let (tag, len) = next_frame(stream, negotiated.batch)?;
    expect(tag, FrameTag::TransferComplete)?;
    if read_body(stream, len)? != meta.session_id.as_bytes() {
        return Err(Fail::protocol("TransferComplete session ID mismatch"));
    }
    if session_bytes != total {
        return Err(Fail::protocol(format!(
            "Byte count mismatch: expected {total} bytes, received {session_bytes} bytes"
        )));
    }
    if let (Some(speed), Some(last)) = (meter.due(true), meta.files.last()) {
        on_event(SessionEvent::Progress(Progress {
            file_index: last.file_index,
            file_name: &last.relative_path,
            file_bytes: last.size_bytes,
            file_total: last.size_bytes,
            session_bytes: total,
            session_total: total,
            speed_bytes_per_sec: speed,
        }));
    }
    // Last point at which this transfer can still be called off.
    check_cancel(cancel)?;
    on_event(SessionEvent::Finishing);

    // Every file is verified. From here the transfer is finished as far as
    // this device is concerned: a cancel or a vanished sender must not leave
    // half of it saved, so neither is consulted again.
    for (index, target) in targets.iter().enumerate() {
        if negotiated.batch && last_write.elapsed() >= HEARTBEAT_EVERY {
            let _ = stream.notify_peer(&frame_bytes(FrameTag::Heartbeat, b""));
            last_write = Instant::now();
        }
        // Recheck ancestors before publishing; never follow a destination link.
        let relative = target.strip_prefix(receive_dir).map_err(|e| Fail::local(e.to_string()))?;
        verify_safe_target_path(receive_dir, relative).map_err(Fail::protocol)?;
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| Fail::disk("Failed to create the destination folder", e))?;
        }
        let saved = publish_received_file(&stage.part(index as u32), target).map_err(Fail::local)?;
        received[index].final_path = saved.to_string_lossy().into_owned();
    }
    // The files are saved. If the confirmation cannot be sent the sender will
    // report a failure, but nothing is missing on this device.
    if !stream.notify_peer(&frame_bytes(FrameTag::TransferAck, b"ACK")) {
        log::warn!("[Receiver] Files saved, but the confirmation was not sent");
    }

    Ok(ReceiveSummary {
        negotiated,
        files: received,
        bytes_received: meter.moved(),
        bytes_resumed: resumed,
        elapsed: meter.elapsed(),
        direct,
    })
}
