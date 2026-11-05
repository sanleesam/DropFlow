use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc,
    },
};

/// Claim an ID exactly once. Network input cannot replace another session's flag.
pub fn register_session(
    map: &mut HashMap<String, Arc<AtomicBool>>,
    id: &str,
    limit: usize,
) -> Result<Arc<AtomicBool>, String> {
    if id.is_empty() || id.len() > 128 {
        return Err("Invalid transfer session ID".into());
    }
    if map.contains_key(id) {
        return Err("Duplicate active transfer session".into());
    }
    if map.len() >= limit.clamp(1, 8) {
        return Err("Busy: transfer limit reached. Try again shortly.".into());
    }
    let flag = Arc::new(AtomicBool::new(false));
    map.insert(id.to_string(), flag.clone());
    Ok(flag)
}

pub fn cancel_session(map: &HashMap<String, Arc<AtomicBool>>, id: &str) {
    if let Some(flag) = map.get(id) {
        flag.store(true, Ordering::Relaxed);
    }
}

/// IDs/names are not authenticated under DFP/1. Remembering a peer never bypasses confirmation.
pub fn needs_approval(
    require_confirmation: bool,
    _remembered: bool,
    _legacy_auto_accept: bool,
) -> bool {
    require_confirmation
}

pub fn report_result<T>(
    result: Result<T, String>,
    on_failure: impl FnOnce(&str),
) -> Result<T, String> {
    if let Err(error) = &result {
        on_failure(error);
    }
    result
}

/// Bound sockets before spawning a worker, including unauthenticated connections.
pub struct ConnectionPermit(Arc<AtomicUsize>);
impl ConnectionPermit {
    pub fn acquire(count: &Arc<AtomicUsize>) -> Option<Self> {
        count
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                (n < 8).then_some(n + 1)
            })
            .ok()?;
        Some(Self(count.clone()))
    }
}
impl Drop for ConnectionPermit {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

/// Wake blocking reads/writes when a user cancels, including the approval wait.
pub struct SocketCancellation {
    stop: std::sync::mpsc::Sender<()>,
    worker: Option<std::thread::JoinHandle<()>>,
}
impl SocketCancellation {
    pub fn watch(stream: &std::net::TcpStream, flag: Arc<AtomicBool>) -> Result<Self, String> {
        let socket = stream.try_clone().map_err(|e| e.to_string())?;
        let (stop, rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || loop {
            if flag.load(Ordering::Relaxed) {
                let _ = socket.shutdown(std::net::Shutdown::Both);
                break;
            }
            match rx.recv_timeout(std::time::Duration::from_millis(50)) {
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                _ => break,
            }
        });
        Ok(Self {
            stop,
            worker: Some(worker),
        })
    }
}
impl Drop for SocketCancellation {
    fn drop(&mut self) {
        let _ = self.stop.send(());
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancellation_interrupts_a_blocked_socket() {
        use std::io::Read;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let mut client = std::net::TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (_peer, _) = listener.accept().unwrap();
        client
            .set_read_timeout(Some(std::time::Duration::from_secs(3)))
            .unwrap();
        let flag = Arc::new(AtomicBool::new(false));
        let _watch = SocketCancellation::watch(&client, flag.clone()).unwrap();
        flag.store(true, Ordering::Relaxed);
        let start = std::time::Instant::now();
        let _ = client.read(&mut [0]);
        assert!(start.elapsed() < std::time::Duration::from_secs(1));
    }
    #[test]
    fn spoofed_remembered_peer_still_requires_approval() {
        for remembered in [false, true] {
            for legacy in [false, true] {
                assert!(needs_approval(true, remembered, legacy));
            }
        }
        assert!(!needs_approval(false, true, true)); // Explicit allow-all remains a separate setting.
    }
    #[test]
    fn duplicate_and_missing_ids_never_replace_or_cancel_another_session() {
        let mut map = HashMap::new();
        let original = register_session(&mut map, "a", 3).unwrap();
        assert!(register_session(&mut map, "a", 3).is_err());
        assert!(Arc::ptr_eq(map.get("a").unwrap(), &original));
        cancel_session(&map, "missing");
        assert!(!original.load(Ordering::Relaxed));
        cancel_session(&map, "a");
        assert!(original.load(Ordering::Relaxed));
        assert!(register_session(&mut map, "", 3).is_err());
    }
    #[test]
    fn bounded_connections_release_their_slots() {
        let count = Arc::new(AtomicUsize::new(0));
        let mut permits = Vec::new();
        for _ in 0..8 {
            permits.push(ConnectionPermit::acquire(&count).unwrap());
        }
        assert!(ConnectionPermit::acquire(&count).is_none());
        permits.pop();
        assert!(ConnectionPermit::acquire(&count).is_some());
        drop(permits);
        assert_eq!(count.load(Ordering::Acquire), 0);
    }
    #[test]
    fn every_session_error_is_reported_once_and_success_is_not_failed() {
        for reason in [
            "header mismatch",
            "disk full",
            "cancelled",
            "disconnected",
            "bad checksum",
            "bad session",
        ] {
            let mut calls = 0;
            assert!(report_result::<()>(Err(reason.into()), |err| {
                assert_eq!(err, reason);
                calls += 1;
            })
            .is_err());
            assert_eq!(calls, 1);
        }
        report_result(Ok(()), |_| panic!("success must not emit failure")).unwrap();
    }
}
