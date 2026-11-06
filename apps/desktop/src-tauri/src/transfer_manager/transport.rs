//! One socket reader/writer, with cancellable waits and unchanged DFP/1 bytes.
//! Do not rely on shutdown from a cloned handle waking a Windows blocking recv.
use polling::{Event, Events, Poller};
use std::{
    io::{self, Read, Write},
    net::{Shutdown, TcpStream},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

const POLL_INTERVAL: Duration = Duration::from_millis(50);

pub struct CancellableStream<'a> {
    socket: &'a mut TcpStream,
    cancellation: Arc<AtomicBool>,
    read_timeout: Option<Duration>,
    write_timeout: Option<Duration>,
    poller: Poller,
    events: Events,
}

impl<'a> CancellableStream<'a> {
    pub fn new(socket: &'a mut TcpStream, cancellation: Arc<AtomicBool>) -> io::Result<Self> {
        // Blocking Winsock timeouts can leave the connection indeterminate.
        // Only retry nonblocking WouldBlock; readiness waits do not consume bytes.
        socket.set_read_timeout(None)?;
        socket.set_write_timeout(None)?;
        socket.set_nonblocking(true)?;
        let poller = Poller::new()?;
        // SAFETY: the exclusive borrow keeps the socket alive and prevents any
        // other reader. Drop deregisters it before releasing that borrow.
        unsafe { poller.add(&*socket, Event::none(0))? };
        let stream = Self {
            socket,
            cancellation,
            read_timeout: Some(Duration::from_secs(15)),
            write_timeout: Some(Duration::from_secs(15)),
            poller,
            events: Events::new(),
        };
        Ok(stream)
    }

    pub fn set_cancellation(&mut self, cancellation: Arc<AtomicBool>) {
        self.cancellation = cancellation;
    }

    pub fn set_read_timeout(&mut self, timeout: Option<Duration>) -> io::Result<()> {
        validate_timeout(timeout)?;
        self.read_timeout = timeout;
        Ok(())
    }

    pub fn set_write_timeout(&mut self, timeout: Option<Duration>) -> io::Result<()> {
        validate_timeout(timeout)?;
        self.write_timeout = timeout;
        Ok(())
    }

    pub fn shutdown(&self, how: Shutdown) -> io::Result<()> {
        self.socket.shutdown(how)
    }

    fn check_wait(&self, start: Instant, timeout: Option<Duration>) -> io::Result<()> {
        if self.cancellation.load(Ordering::Relaxed) {
            // Interrupted would be retried forever by read_exact/write_all.
            return Err(io::Error::new(
                io::ErrorKind::ConnectionAborted,
                "Transfer cancelled by user",
            ));
        }
        if timeout.is_some_and(|limit| start.elapsed() >= limit) {
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "Transfer socket timed out",
            ));
        }
        Ok(())
    }

    fn wait_ready(
        &mut self,
        event: Event,
        start: Instant,
        timeout: Option<Duration>,
    ) -> io::Result<()> {
        self.check_wait(start, timeout)?;
        let wait = timeout.map_or(POLL_INTERVAL, |limit| {
            limit.saturating_sub(start.elapsed()).min(POLL_INTERVAL)
        });
        self.poller.modify(&*self.socket, event)?;
        self.events.clear();
        match self.poller.wait(&mut self.events, Some(wait)) {
            Ok(_) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::Interrupted => Ok(()),
            Err(error) => Err(error),
        }
    }
}

impl Drop for CancellableStream<'_> {
    fn drop(&mut self) {
        // Even if deregistration fails, the poller is dropped while the
        // exclusively borrowed socket is still alive.
        if let Err(error) = self.poller.delete(&*self.socket) {
            log::warn!("Transfer socket deregistration failed: {error}");
        }
    }
}

fn validate_timeout(timeout: Option<Duration>) -> io::Result<()> {
    if timeout.is_some_and(|value| value.is_zero()) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Socket timeout must be non-zero",
        ));
    }
    Ok(())
}

impl Read for CancellableStream<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        let start = Instant::now();
        loop {
            self.check_wait(start, self.read_timeout)?;
            match self.socket.read(buffer) {
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    self.wait_ready(Event::readable(0), start, self.read_timeout)?;
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                result => return result,
            }
        }
    }
}

impl Write for CancellableStream<'_> {
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        let start = Instant::now();
        loop {
            self.check_wait(start, self.write_timeout)?;
            match self.socket.write(buffer) {
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    self.wait_ready(Event::writable(0), start, self.write_timeout)?;
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                result => return result,
            }
        }
    }
    fn flush(&mut self) -> io::Result<()> {
        self.check_wait(Instant::now(), None)?;
        self.socket.flush()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::TcpListener, sync::mpsc, thread};

    fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        (client, listener.accept().unwrap().0)
    }

    #[test]
    fn readiness_waits_do_not_truncate_fragmented_protocol_reads() {
        let (mut client, mut peer) = pair();
        let worker = thread::spawn(move || {
            peer.write_all(b"HELLO").unwrap();
            thread::sleep(Duration::from_millis(180));
            peer.write_all(b" DFP/1").unwrap();
        });
        let mut stream =
            CancellableStream::new(&mut client, Arc::new(AtomicBool::new(false))).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut bytes = [0; 11];
        stream.read_exact(&mut bytes).unwrap();
        assert_eq!(&bytes, b"HELLO DFP/1");
        worker.join().unwrap();
    }

    #[test]
    fn inactivity_budget_and_zero_timeout_are_preserved() {
        let (mut client, _peer) = pair();
        let mut stream =
            CancellableStream::new(&mut client, Arc::new(AtomicBool::new(false))).unwrap();
        assert!(stream.set_read_timeout(Some(Duration::ZERO)).is_err());
        stream
            .set_read_timeout(Some(Duration::from_millis(160)))
            .unwrap();
        let start = Instant::now();
        assert_eq!(
            stream.read(&mut [0]).unwrap_err().kind(),
            io::ErrorKind::TimedOut
        );
        assert!(start.elapsed() >= Duration::from_millis(160));
        assert!(start.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn backpressure_preserves_every_byte_when_writes_resume() {
        let (mut client, mut peer) = pair();
        const TOTAL: usize = 32 * 1024 * 1024;
        let worker = thread::spawn(move || {
            peer.set_read_timeout(Some(Duration::from_secs(10)))
                .unwrap();
            thread::sleep(Duration::from_millis(180));
            let mut received = 0;
            let mut buffer = [0; 64 * 1024];
            while received < TOTAL {
                let n = peer.read(&mut buffer).unwrap();
                assert!(n > 0, "stream ended before all bytes arrived");
                for (index, byte) in buffer[..n].iter().enumerate() {
                    assert_eq!(*byte, ((received + index) % 251) as u8);
                }
                received += n;
            }
            peer.write_all(b"OK").unwrap();
        });
        let mut stream =
            CancellableStream::new(&mut client, Arc::new(AtomicBool::new(false))).unwrap();
        stream
            .set_write_timeout(Some(Duration::from_secs(10)))
            .unwrap();
        let bytes: Vec<_> = (0..TOTAL).map(|index| (index % 251) as u8).collect();
        stream.write_all(&bytes).unwrap();
        let mut ack = [0; 2];
        stream.read_exact(&mut ack).unwrap();
        assert_eq!(&ack, b"OK");
        worker.join().unwrap();
    }

    #[test]
    fn dfp1_roundtrip_preserves_bytes_and_approval_phase() {
        use super::super::protocol::*;
        let (mut client, mut peer) = pair();
        let worker = thread::spawn(move || {
            let mut stream =
                CancellableStream::new(&mut peer, Arc::new(AtomicBool::new(false))).unwrap();
            perform_incoming_handshake(&mut stream).unwrap();
            let metadata = read_transfer_request(&mut stream).unwrap();
            assert_eq!(metadata.session_id, "roundtrip");
            thread::sleep(Duration::from_millis(180));
            write_response_frame(&mut stream, FrameTag::TransferAccept, "ACCEPTED").unwrap();
            let header = read_file_header(&mut stream).unwrap();
            assert_eq!(header.size_bytes, 3);
            write_transfer_ack(
                &mut stream,
                &TransferAckPayload {
                    status: "ACCEPTED".into(),
                    resume_offset: 0,
                },
            )
            .unwrap();
            let (tag, len) = read_frame_header(&mut stream).unwrap();
            assert_eq!(tag, FrameTag::DataChunk);
            assert_eq!(len, 3);
            let mut bytes = [0; 3];
            stream.read_exact(&mut bytes).unwrap();
            assert_eq!(&bytes, b"abc");
            let complete = read_file_complete(&mut stream).unwrap();
            assert_eq!(complete.bytes_written, 3);
            let (tag, len) = read_frame_header(&mut stream).unwrap();
            assert_eq!(tag, FrameTag::TransferComplete);
            let mut id = vec![0; len as usize];
            stream.read_exact(&mut id).unwrap();
            assert_eq!(id, b"roundtrip");
            write_response_frame(&mut stream, FrameTag::TransferAck, "ACK").unwrap();
        });
        let mut stream =
            CancellableStream::new(&mut client, Arc::new(AtomicBool::new(false))).unwrap();
        perform_outgoing_handshake(&mut stream).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(75)))
            .unwrap();
        write_transfer_request(
            &mut stream,
            &TransferMetadata {
                session_id: "roundtrip".into(),
                sender_id: "fixture".into(),
                sender_name: "Fixture".into(),
                sender_platform: "Test".into(),
                total_files: 1,
                total_size_bytes: 3,
                files: vec![FileMetadata {
                    file_index: 0,
                    relative_path: "a.txt".into(),
                    size_bytes: 3,
                    sha256_checksum: String::new(),
                    final_path: String::new(),
                }],
            },
        )
        .unwrap();
        let (tag, len) = read_frame_header(&mut stream).unwrap();
        assert_eq!(tag, FrameTag::TransferAccept);
        let mut payload = vec![0; len as usize];
        stream.read_exact(&mut payload).unwrap();
        assert_eq!(payload, b"ACCEPTED");
        stream
            .set_read_timeout(Some(Duration::from_secs(15)))
            .unwrap();
        write_file_header(
            &mut stream,
            &FileHeaderPayload {
                file_index: 0,
                relative_path: "a.txt".into(),
                size_bytes: 3,
                sha256_checksum: String::new(),
                resume_offset: 0,
            },
        )
        .unwrap();
        assert_eq!(read_transfer_ack(&mut stream).unwrap().resume_offset, 0);
        write_frame_header(&mut stream, FrameTag::DataChunk, 3).unwrap();
        stream.write_all(b"abc").unwrap();
        write_file_complete(
            &mut stream,
            &FileCompletePayload {
                file_index: 0,
                bytes_written: 3,
                sha256_checksum: String::new(),
            },
        )
        .unwrap();
        write_frame_header(&mut stream, FrameTag::TransferComplete, 9).unwrap();
        stream.write_all(b"roundtrip").unwrap();
        let (tag, len) = read_frame_header(&mut stream).unwrap();
        assert_eq!(tag, FrameTag::TransferAck);
        let mut ack = vec![0; len as usize];
        stream.read_exact(&mut ack).unwrap();
        assert_eq!(ack, b"ACK");
        worker.join().unwrap();
    }

    #[test]
    fn cancellation_interrupts_blocked_writes_without_a_socket_clone() {
        let (mut client, _peer) = pair();
        let flag = Arc::new(AtomicBool::new(false));
        let worker_flag = flag.clone();
        let (ready_tx, ready_rx) = mpsc::channel();
        let (tx, rx) = mpsc::channel();
        let worker = thread::spawn(move || {
            let mut stream = CancellableStream::new(&mut client, worker_flag).unwrap();
            stream
                .set_write_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            ready_tx.send(()).unwrap();
            let result = (|| {
                for _ in 0..2048 {
                    stream.write_all(&[7; 65536])?;
                }
                Ok::<_, io::Error>(())
            })();
            tx.send(result.map_err(|error| error.kind())).unwrap();
        });
        ready_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        thread::sleep(Duration::from_millis(180));
        assert!(matches!(rx.try_recv(), Err(mpsc::TryRecvError::Empty)));
        flag.store(true, Ordering::Relaxed);
        assert_eq!(
            rx.recv_timeout(Duration::from_secs(1)).unwrap(),
            Err(io::ErrorKind::ConnectionAborted)
        );
        worker.join().unwrap();
    }
}
