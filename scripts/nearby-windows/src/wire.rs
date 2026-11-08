//! Synthetic probe only. No filenames, filesystem access, or production protocol.
use sha2::{Digest, Sha256};
use std::io::{self, Read, Write};

#[cfg(windows)]
pub const PORT: u16 = 47638;
const MAGIC: &[u8; 8] = b"DFNLAB01";
const BLOCK: usize = 64 * 1024;
const BLOCKS: usize = 256;

fn pattern(seed: u8) -> [u8; BLOCK] {
    std::array::from_fn(|i| seed.wrapping_add((i ^ (i >> 8)) as u8))
}

fn require(condition: bool, message: &str) -> io::Result<()> {
    if condition {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::InvalidData, message))
    }
}

fn receive(stream: &mut impl Read, seed: u8) -> io::Result<[u8; 32]> {
    let expected = pattern(seed);
    let mut block = [0; BLOCK];
    let mut hash = Sha256::new();
    for _ in 0..BLOCKS {
        stream.read_exact(&mut block)?;
        require(block == expected, "Unexpected synthetic payload")?;
        hash.update(block);
    }
    Ok(hash.finalize().into())
}

fn send(stream: &mut impl Write, seed: u8) -> io::Result<[u8; 32]> {
    let block = pattern(seed);
    let mut hash = Sha256::new();
    for _ in 0..BLOCKS {
        stream.write_all(&block)?;
        hash.update(block);
    }
    Ok(hash.finalize().into())
}

pub fn host(stream: &mut (impl Read + Write)) -> io::Result<()> {
    let mut magic = [0; MAGIC.len()];
    stream.read_exact(&mut magic)?;
    require(&magic == MAGIC, "Not a DropFlow lab probe")?;
    let received = receive(stream, 0x19)?;
    let sent = send(stream, 0xa2)?;
    let mut receipt = [0; 32];
    stream.read_exact(&mut receipt)?;
    require(receipt == sent, "Android receipt did not match")?;
    stream.write_all(&received)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{TcpListener, TcpStream};
    use std::time::Duration;

    #[test]
    fn bidirectional_probe_checks_both_receipts() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (mut server, _) = listener.accept().unwrap();
        for socket in [&client, &server] {
            socket
                .set_read_timeout(Some(Duration::from_secs(10)))
                .unwrap();
            socket
                .set_write_timeout(Some(Duration::from_secs(10)))
                .unwrap();
        }
        let worker = std::thread::spawn(move || host(&mut server).unwrap());
        client.write_all(MAGIC).unwrap();
        let sent = send(&mut client, 0x19).unwrap();
        let received = receive(&mut client, 0xa2).unwrap();
        // Shared golden digests with NearbyLabWireTest.
        assert_eq!(
            format!("{:x}", sha2::digest::Output::<Sha256>::from(sent)),
            "bee90c7a41e5acd47a8f107974fd8b8a8b89fb52828d6b700696f34bdd926ee9",
        );
        assert_eq!(
            format!("{:x}", sha2::digest::Output::<Sha256>::from(received)),
            "a8dbf0591ada7c90e6ac885810b6dd3d2f59d5641fba32322ccb3fc6135e1610",
        );
        client.write_all(&received).unwrap();
        let mut receipt = [0; 32];
        client.read_exact(&mut receipt).unwrap();
        assert_eq!(receipt, sent);
        worker.join().unwrap();
    }

    #[test]
    fn rejects_wrong_magic_and_corruption_without_responding() {
        for input in [
            b"NOTALINK".to_vec(),
            [MAGIC.as_slice(), &[0; BLOCK]].concat(),
        ] {
            let len = input.len();
            let mut stream = io::Cursor::new(input);
            assert_eq!(
                host(&mut stream).unwrap_err().kind(),
                io::ErrorKind::InvalidData
            );
            assert_eq!(stream.into_inner().len(), len);
        }
    }

    #[test]
    fn host_rejects_wrong_final_receipt() {
        let block = pattern(0x19);
        let mut input = Vec::with_capacity(MAGIC.len() + BLOCK * BLOCKS + 32);
        input.extend_from_slice(MAGIC);
        for _ in 0..BLOCKS {
            input.extend_from_slice(&block);
        }
        input.extend_from_slice(&[0; 32]);
        struct Split(io::Cursor<Vec<u8>>, usize);
        impl Read for Split {
            fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
                self.0.read(output)
            }
        }
        impl Write for Split {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
                self.1 += bytes.len();
                Ok(bytes.len())
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        let mut stream = Split(io::Cursor::new(input), 0);
        assert_eq!(
            host(&mut stream).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
        assert_eq!(stream.1, BLOCK * BLOCKS); // No success receipt.
    }
}
