//! Bounded, reusable DFP/1 data path, independent of TCP and the UI.
//! The wire format, control messages, integrity checks and final ACK are unchanged.
use std::io::{self, Read, Write};

use super::protocol::{encode_frame_header, FrameTag, FRAME_HEADER_SIZE, MAX_CHUNK_SIZE};

const BULK_CHUNK_SIZE: usize = 256 * 1024;

pub(super) struct ChunkWriter {
    frame: Vec<u8>,
}

impl ChunkWriter {
    pub(super) fn new() -> Self {
        Self {
            frame: vec![0; FRAME_HEADER_SIZE + BULK_CHUNK_SIZE],
        }
    }

    pub(super) fn payload(&mut self) -> &mut [u8] {
        &mut self.frame[FRAME_HEADER_SIZE..]
    }

    pub(super) fn send(&mut self, writer: &mut impl Write, count: usize) -> io::Result<()> {
        if count == 0 || count > BULK_CHUNK_SIZE {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Invalid data chunk length",
            ));
        }
        self.frame[..FRAME_HEADER_SIZE]
            .copy_from_slice(&encode_frame_header(FrameTag::DataChunk, count as u32));
        writer.write_all(&self.frame[..FRAME_HEADER_SIZE + count])
    }
}

#[derive(Default)]
pub(super) struct ChunkReader {
    buffer: Vec<u8>,
}

impl ChunkReader {
    pub(super) fn read<'a>(
        &'a mut self,
        reader: &mut impl Read,
        length: usize,
        remaining: u64,
    ) -> io::Result<&'a [u8]> {
        if length == 0 || length > MAX_CHUNK_SIZE || length as u64 > remaining {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Invalid data chunk length",
            ));
        }
        // Keep the high-water buffer across files and frames. Never expose its stale tail.
        if self.buffer.len() < length {
            self.buffer.resize(length, 0);
        }
        let payload = &mut self.buffer[..length];
        reader.read_exact(payload)?;
        Ok(payload)
    }
}

#[cfg(test)]
mod tests {
    use super::super::protocol::{read_frame_header, write_frame_header, DEFAULT_CHUNK_SIZE};
    use super::super::transport::CancellableStream;
    use super::*;
    use sha2::{Digest, Sha256};
    use std::{
        io::Cursor,
        net::{TcpListener, TcpStream},
        sync::{atomic::AtomicBool, Arc},
        thread,
        time::{Duration, Instant},
    };

    #[test]
    fn frames_match_legacy_encoding_and_do_not_leak_the_buffer_tail() {
        let mut writer = ChunkWriter::new();
        writer.payload().fill(0xFF);
        writer.payload()[..3].copy_from_slice(b"abc");
        let mut frame = Vec::new();
        writer.send(&mut frame, 3).unwrap();
        let mut expected = Vec::new();
        write_frame_header(&mut expected, FrameTag::DataChunk, 3).unwrap();
        expected.extend_from_slice(b"abc");
        assert_eq!(frame, expected);
        assert!(writer.send(&mut frame, 0).is_err());
        assert!(writer.send(&mut frame, BULK_CHUNK_SIZE + 1).is_err());
    }

    #[test]
    fn receiver_reuses_buffer_and_rejects_bad_lengths_or_truncated_frames() {
        let mut reader = ChunkReader::default();
        let first = reader
            .read(&mut Cursor::new(b"abcd"), 4, 4)
            .unwrap()
            .as_ptr();
        let second = reader.read(&mut Cursor::new(b"xy"), 2, 2).unwrap();
        assert_eq!(second, b"xy");
        assert_eq!(second.as_ptr(), first);
        for (length, remaining) in [(0, 5), (MAX_CHUNK_SIZE + 1, u64::MAX), (4, 3)] {
            let mut input = Cursor::new(b"abcd");
            assert_eq!(
                reader
                    .read(&mut input, length, remaining)
                    .unwrap_err()
                    .kind(),
                io::ErrorKind::InvalidData
            );
            assert_eq!(input.position(), 0);
        }
        assert_eq!(
            reader
                .read(&mut Cursor::new(b"z"), 2, 2)
                .unwrap_err()
                .kind(),
            io::ErrorKind::UnexpectedEof
        );
    }

    fn loopback(optimized: bool, data: &[u8]) -> Duration {
        let count = data.len();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut socket = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (mut receiver_socket, _) = listener.accept().unwrap();
        let worker = thread::spawn(move || {
            let mut receiver =
                CancellableStream::new(&mut receiver_socket, Arc::new(AtomicBool::new(false)))
                    .unwrap();
            let mut reader = ChunkReader::default();
            let mut hasher = Sha256::new();
            let mut remaining = count;
            while remaining > 0 {
                let (tag, len) = read_frame_header(&mut receiver).unwrap();
                assert_eq!(tag, FrameTag::DataChunk);
                if optimized {
                    hasher.update(
                        reader
                            .read(&mut receiver, len as usize, remaining as u64)
                            .unwrap(),
                    );
                } else {
                    let mut bytes = vec![0; len as usize];
                    receiver.read_exact(&mut bytes).unwrap();
                    hasher.update(&bytes);
                }
                remaining -= len as usize;
            }
            receiver.write_all(&hasher.finalize()).unwrap();
        });

        let mut sender =
            CancellableStream::new(&mut socket, Arc::new(AtomicBool::new(false))).unwrap();
        let chunk_size = if optimized {
            BULK_CHUNK_SIZE
        } else {
            DEFAULT_CHUNK_SIZE
        };
        let mut writer = ChunkWriter::new();
        let mut legacy = vec![0; DEFAULT_CHUNK_SIZE];
        let mut hasher = Sha256::new();
        let mut source = Cursor::new(data);
        let start = Instant::now();
        for offset in (0..count).step_by(chunk_size) {
            let n = (count - offset).min(chunk_size);
            if optimized {
                source.read_exact(&mut writer.payload()[..n]).unwrap();
                hasher.update(&writer.payload()[..n]);
                writer.send(&mut sender, n).unwrap();
            } else {
                source.read_exact(&mut legacy[..n]).unwrap();
                hasher.update(&legacy[..n]);
                write_frame_header(&mut sender, FrameTag::DataChunk, n as u32).unwrap();
                sender.write_all(&legacy[..n]).unwrap();
            }
        }
        let mut received_hash = [0; 32];
        sender.read_exact(&mut received_hash).unwrap();
        let elapsed = start.elapsed();
        assert_eq!(&received_hash[..], &hasher.finalize()[..]);
        worker.join().unwrap();
        elapsed
    }

    #[test]
    fn optimized_frames_transfer_exact_bytes_over_tcp() {
        let bytes: Vec<u8> = (0..BULK_CHUNK_SIZE * 2 + 1)
            .map(|i| (i % 251) as u8)
            .collect();
        loopback(true, &bytes);
    }

    #[test]
    #[ignore = "manual throughput comparison; run optimized with --ignored --nocapture"]
    fn compare_bulk_throughput() {
        let bytes: Vec<u8> = (0..128 * 1024 * 1024).map(|i| (i % 251) as u8).collect();
        let mut legacy = Vec::new();
        let mut optimized = Vec::new();
        for run in 0..5 {
            for fast in [run % 2 != 0, run % 2 == 0] {
                let elapsed = loopback(fast, &bytes);
                if fast {
                    optimized.push(elapsed);
                } else {
                    legacy.push(elapsed);
                }
                println!(
                    "{run}: {} {:.1} MiB/s",
                    if fast { "candidate" } else { "legacy" },
                    128.0 / elapsed.as_secs_f64()
                );
            }
        }
        legacy.sort();
        optimized.sort();
        println!(
            "median: {:.1} → {:.1} MiB/s ({:.2}×), synthetic loopback only",
            128.0 / legacy[2].as_secs_f64(),
            128.0 / optimized[2].as_secs_f64(),
            legacy[2].as_secs_f64() / optimized[2].as_secs_f64()
        );
    }
}
