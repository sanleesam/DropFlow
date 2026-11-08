//! Experimental bulk session, not connected to the public DFP/1 listener.
//!
//! Use only after authenticated, encrypted pairing, batch approval, and
//! exchange of the approved file lengths and receiver resume points. That
//! negotiation/storage integration is deliberately outside this data plane.
use sha2::{Digest, Sha256};
use std::io::{self, Read, Seek, SeekFrom, Write};

const BLOCK: usize = 256 * 1024;
const HEADER: usize = 4 + 8 + 4 + 32; // file index, offset, length, cumulative SHA-256
const START: &[u8; 8] = b"DFB2DATA";
const DONE: &[u8; 8] = b"DFB2DONE";
const MAX_FILES: usize = 4096;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ResumePoint {
    pub offset: u64,
    pub sha256: [u8; 32],
}

pub struct Checkpoint {
    size: u64,
    offset: u64,
    hash: Sha256,
}

impl Checkpoint {
    pub fn fresh(size: u64) -> Self {
        Self {
            size,
            offset: 0,
            hash: Sha256::new(),
        }
    }

    /// Recheck the stored prefix rather than trusting its length after a crash.
    /// The caller owns the staging file and truncates uncommitted tail bytes.
    pub fn restore(
        size: u64,
        point: ResumePoint,
        stored: &mut (impl Read + Seek),
    ) -> io::Result<Self> {
        check(point.offset <= size, "Checkpoint beyond file length")?;
        let hash = checked_prefix(stored, point, &mut vec![0; BLOCK])?;
        Ok(Self {
            size,
            offset: point.offset,
            hash,
        })
    }

    pub fn resume_point(&self) -> ResumePoint {
        ResumePoint {
            offset: self.offset,
            sha256: self.hash.clone().finalize().into(),
        }
    }
}

pub struct Source<R> {
    pub bytes: R,
    pub size: u64,
}

fn check(condition: bool, message: &'static str) -> io::Result<()> {
    if condition {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::InvalidData, message))
    }
}

fn checked_prefix(
    source: &mut (impl Read + Seek),
    point: ResumePoint,
    buffer: &mut [u8],
) -> io::Result<Sha256> {
    source.seek(SeekFrom::Start(0))?;
    let mut remaining = point.offset;
    let mut hash = Sha256::new();
    while remaining > 0 {
        let n = remaining.min(buffer.len() as u64) as usize;
        source.read_exact(&mut buffer[..n])?;
        hash.update(&buffer[..n]);
        remaining -= n as u64;
    }
    let actual: [u8; 32] = hash.clone().finalize().into();
    check(
        actual == point.sha256,
        "The staged prefix or source changed",
    )?;
    Ok(hash)
}

fn marker(kind: &[u8; 8], id: [u8; 16]) -> [u8; 24] {
    let mut bytes = [0; 24];
    bytes[..8].copy_from_slice(kind);
    bytes[8..].copy_from_slice(&id);
    bytes
}

/// Streams all preapproved files in order, without per-file round trips.
/// Returns success only for the exact batch's committed receipt.
pub fn send_batch<R: Read + Seek>(
    link: &mut (impl Read + Write),
    id: [u8; 16],
    files: &mut [Source<R>],
    points: &[ResumePoint],
) -> io::Result<()> {
    check(
        !files.is_empty() && files.len() <= MAX_FILES && files.len() == points.len(),
        "Invalid approved batch",
    )?;
    let mut frame = vec![0; HEADER + BLOCK];
    let mut prefix_hashes = Vec::with_capacity(files.len());
    for (file, point) in files.iter_mut().zip(points) {
        check(
            point.offset <= file.size,
            "Resume offset beyond approved length",
        )?;
        prefix_hashes.push(checked_prefix(
            &mut file.bytes,
            *point,
            &mut frame[HEADER..],
        )?);
    }
    link.write_all(&marker(START, id))?;
    for (index, ((file, point), mut hash)) in
        files.iter_mut().zip(points).zip(prefix_hashes).enumerate()
    {
        let mut offset = point.offset;
        while offset < file.size {
            let len = (file.size - offset).min(BLOCK as u64) as usize;
            file.bytes.read_exact(&mut frame[HEADER..HEADER + len])?;
            hash.update(&frame[HEADER..HEADER + len]);
            frame[..4].copy_from_slice(&(index as u32).to_be_bytes());
            frame[4..12].copy_from_slice(&offset.to_be_bytes());
            frame[12..16].copy_from_slice(&(len as u32).to_be_bytes());
            frame[16..HEADER].copy_from_slice(&hash.clone().finalize());
            link.write_all(&frame[..HEADER + len])?;
            offset += len as u64;
        }
        check(
            file.bytes.read(&mut [0])? == 0,
            "Source grew beyond the approved length",
        )?;
    }
    // An end marker proves the sender reached EOF on every approved source.
    link.write_all(&marker(DONE, id))?;
    link.flush()?;
    let mut ack = [0; 24];
    link.read_exact(&mut ack)?;
    check(ack == marker(DONE, id), "Missing committed batch receipt")
}

/// Only verified bytes reach `commit`. The callback must durably store the
/// bytes and new resume point (or roll back on error). `publish` must commit
/// once per batch ID, including if the acknowledgement is lost and retried.
pub fn receive_batch(
    link: &mut (impl Read + Write),
    id: [u8; 16],
    files: &mut [Checkpoint],
    mut commit: impl FnMut(usize, u64, &[u8], ResumePoint) -> io::Result<()>,
    publish: impl FnOnce([u8; 16]) -> io::Result<()>,
) -> io::Result<()> {
    check(
        !files.is_empty() && files.len() <= MAX_FILES,
        "Invalid approved batch",
    )?;
    let mut control = [0; 24];
    link.read_exact(&mut control)?;
    check(control == marker(START, id), "Unexpected batch ID")?;
    let mut buffer = vec![0; BLOCK];
    let mut header = [0; HEADER];
    for (index, file) in files.iter_mut().enumerate() {
        while file.offset < file.size {
            link.read_exact(&mut header)?;
            let len = (file.size - file.offset).min(BLOCK as u64) as usize;
            check(
                header[..4] == (index as u32).to_be_bytes()
                    && header[4..12] == file.offset.to_be_bytes()
                    && header[12..16] == (len as u32).to_be_bytes(),
                "Unexpected block index, offset or length",
            )?;
            let payload = &mut buffer[..len];
            link.read_exact(payload)?;
            let mut hash = file.hash.clone();
            hash.update(&*payload);
            let point = ResumePoint {
                offset: file.offset + len as u64,
                sha256: hash.clone().finalize().into(),
            };
            check(point.sha256[..] == header[16..], "Corrupt batch block")?;
            commit(index, file.offset, payload, point)?;
            file.hash = hash;
            file.offset = point.offset;
        }
    }
    link.read_exact(&mut control)?;
    check(
        control == marker(DONE, id),
        "Sender did not finish approved batch",
    )?;
    publish(id)?;
    link.write_all(&marker(DONE, id))?;
    link.flush()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    const ID: [u8; 16] = [13; 16];

    #[test]
    fn one_tcp_stream_completes_a_preapproved_batch() {
        use std::{
            net::{TcpListener, TcpStream},
            thread,
            time::Duration,
        };
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut socket = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        socket
            .set_write_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let (mut peer, _) = listener.accept().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        peer.set_write_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let worker = thread::spawn(move || {
            let mut states: Vec<_> = (0..64).map(|_| Checkpoint::fresh(4096)).collect();
            let mut committed = 0;
            receive_batch(
                &mut peer,
                ID,
                &mut states,
                |index, offset, bytes, point| {
                    assert_eq!(offset, 0);
                    assert_eq!(point.offset, 4096);
                    assert_eq!(bytes, &[index as u8; 4096]);
                    committed += 1;
                    Ok(())
                },
                |_| Ok(()),
            )
            .unwrap();
            assert_eq!(committed, 64);
        });
        let mut files: Vec<_> = (0..64)
            .map(|i| Source {
                bytes: Cursor::new([i as u8; 4096]),
                size: 4096,
            })
            .collect();
        let points = [Checkpoint::fresh(4096).resume_point(); 64];
        send_batch(&mut socket, ID, &mut files, &points).unwrap();
        worker.join().unwrap();
    }

    struct Fixture {
        input: Cursor<Vec<u8>>,
        output: Vec<u8>,
    }

    impl Fixture {
        fn with_input(input: Vec<u8>) -> Self {
            Self {
                input: Cursor::new(input),
                output: Vec::new(),
            }
        }
    }
    impl Read for Fixture {
        fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
            self.input.read(bytes)
        }
    }
    impl Write for Fixture {
        fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
            self.output.write(bytes)
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    fn encode(bytes: &[&[u8]], points: &[ResumePoint]) -> Vec<u8> {
        let mut link = Fixture::with_input(marker(DONE, ID).to_vec());
        let mut sources: Vec<_> = bytes
            .iter()
            .map(|data| Source {
                bytes: Cursor::new(*data),
                size: data.len() as u64,
            })
            .collect();
        send_batch(&mut link, ID, &mut sources, points).unwrap();
        link.output
    }

    #[test]
    fn interrupted_batch_resumes_from_last_verified_block_after_reloading_checkpoint() {
        let bytes: Vec<u8> = (0..BLOCK + 41).map(|i| (i % 251) as u8).collect();
        let mut files = [
            Checkpoint::fresh(bytes.len() as u64),
            Checkpoint::fresh(0),
            Checkpoint::fresh(4),
        ];
        let start = files.each_ref().map(Checkpoint::resume_point);
        let mut wire = encode(&[&bytes, b"", b"tail"], &start);
        wire.truncate(24 + HEADER + BLOCK + HEADER + 5);
        let mut stage = [Vec::new(), Vec::new(), Vec::new()];
        let mut link = Fixture::with_input(wire);
        let append = |files: &mut [Vec<u8>], index: usize, offset: u64, payload: &[u8]| {
            assert_eq!(files[index].len() as u64, offset);
            files[index].extend_from_slice(payload);
            Ok(())
        };
        let error = receive_batch(
            &mut link,
            ID,
            &mut files,
            |i, off, p, _| append(&mut stage, i, off, p),
            |_| panic!("Interrupted batch cannot publish"),
        )
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::UnexpectedEof);
        assert!(link.output.is_empty());
        assert_eq!(stage[0], bytes[..BLOCK]);
        let points = files.each_ref().map(Checkpoint::resume_point);
        files[0] = Checkpoint::restore(bytes.len() as u64, points[0], &mut Cursor::new(&stage[0]))
            .unwrap();
        let resumed = encode(&[&bytes, b"", b"tail"], &points);
        assert_eq!(resumed.len(), 24 + HEADER + 41 + HEADER + 4 + 24);
        let mut link = Fixture::with_input(resumed);
        receive_batch(
            &mut link,
            ID,
            &mut files,
            |i, off, p, _| append(&mut stage, i, off, p),
            |id| {
                assert_eq!(id, ID);
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(stage[0], bytes);
        assert!(stage[1].is_empty());
        assert_eq!(stage[2], b"tail");
        assert_eq!(link.output, marker(DONE, ID));
    }

    #[test]
    fn corrupted_payload_and_failed_storage_never_advance_or_acknowledge() {
        for corrupt in [true, false] {
            let mut files = [Checkpoint::fresh(3)];
            let start = files[0].resume_point();
            let mut wire = encode(&[b"abc"], &[start]);
            if corrupt {
                wire[24 + HEADER] ^= 1;
            }
            let mut link = Fixture::with_input(wire);
            receive_batch(
                &mut link,
                ID,
                &mut files,
                |_, _, _, _| {
                    assert!(!corrupt, "Corrupt block must not reach storage");
                    Err(io::Error::other("Storage rejected block"))
                },
                |_| panic!("Failed batch cannot publish"),
            )
            .unwrap_err();
            assert_eq!(files[0].resume_point(), start);
            assert!(link.output.is_empty());
        }
    }

    #[test]
    fn changed_resume_source_and_wrong_commit_receipt_are_rejected() {
        let mut link = Fixture::with_input(marker(DONE, [0; 16]).to_vec());
        let mut source = [Source {
            bytes: Cursor::new(b"abc"),
            size: 3,
        }];
        let invalid = ResumePoint {
            offset: 1,
            sha256: Sha256::digest(b"z").into(),
        };
        assert!(send_batch(&mut link, ID, &mut source, &[invalid]).is_err());
        assert!(link.output.is_empty());
        assert!(send_batch(
            &mut link,
            ID,
            &mut source,
            &[Checkpoint::fresh(3).resume_point()]
        )
        .is_err());
    }

    #[test]
    fn publication_failure_leaves_no_ack_and_retry_replays_only_receipt() {
        let mut files = [Checkpoint::fresh(3)];
        let wire = encode(&[b"abc"], &[files[0].resume_point()]);
        let mut link = Fixture::with_input(wire);
        receive_batch(
            &mut link,
            ID,
            &mut files,
            |_, _, _, _| Ok(()),
            |_| Err(io::Error::other("Publication failed")),
        )
        .unwrap_err();
        assert!(link.output.is_empty());
        let retry = encode(&[b"abc"], &[files[0].resume_point()]);
        assert_eq!(retry.len(), 48);
        let mut link = Fixture::with_input(retry);
        receive_batch(
            &mut link,
            ID,
            &mut files,
            |_, _, _, _| panic!("Already committed bytes must not be resent"),
            |_| Ok(()),
        )
        .unwrap();
        assert_eq!(link.output, marker(DONE, ID));
    }
}
