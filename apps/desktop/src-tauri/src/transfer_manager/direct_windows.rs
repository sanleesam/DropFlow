//! The Windows side of a direct link (TRANSFER_PROTOCOL.md, section 7.5).
//!
//! A helper program bundled with the app keeps a Wi-Fi Direct network up for
//! as long as its stdin stays open, and reports on stdout who joined. This
//! file starts it, reads those reports, and opens the one connection to the
//! device that joined. Windows only ever connects out, so no firewall rule is
//! needed, and nothing about the PC's own network is touched. Any failure
//! here leaves the transfer on the network it started on.
//!
//! Compiled for Windows. The tests run the same code anywhere, against a
//! stand-in for the helper.
use std::io::{self, BufRead, BufReader};
use std::net::{Ipv4Addr, SocketAddrV4, TcpStream};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};
use socket2::{Domain, Protocol, Socket, Type};

use super::protocol::{DirectCaps, DirectOffer};
use super::session::{DirectLink, HostedLink, JoinedLink};

/// The helper is a console program; this keeps its window from flashing up.
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

const READY_TIMEOUT: Duration = Duration::from_secs(15);
/// The engine's keep-alive runs at least this often while the helper starts.
const TICK_EVERY: Duration = Duration::from_millis(200);
/// How long Windows gets to report the joined device's addresses.
const PEER_TIMEOUT: Duration = Duration::from_secs(5);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// How long the helper gets to take the network down before it is killed.
const EXIT_GRACE: Duration = Duration::from_secs(2);
const MAX_PAIRS: usize = 8;

/// One network name per PC, so one helper at a time: a second transfer stays
/// on the normal network rather than fight the first for the radio.
static HOSTING: AtomicBool = AtomicBool::new(false);

// ─── What the helper says ────────────────────────────────────────────────────

#[derive(Debug, PartialEq, Eq)]
enum Report {
    Ready,
    /// A device joined: this PC's address on the private network, and its.
    Peer { local: Ipv4Addr, remote: Ipv4Addr },
    Note(String),
    Lost(String),
    Error(String),
}

/// One line of the helper's stdout. Anything unexpected is ignored.
fn parse(line: &str) -> Option<Report> {
    let (event, rest) = line.split_once('\t').unwrap_or((line, ""));
    let text = |fallback: &str| -> String {
        let text: String = rest.chars().filter(|c| !c.is_control()).take(200).collect();
        if text.trim().is_empty() { fallback.to_string() } else { text }
    };
    match event {
        "ready" if rest.is_empty() => Some(Report::Ready),
        "peer" => {
            let (local, remote) = rest.split_once('\t')?;
            let (local, remote) = (private_address(local)?, private_address(remote)?);
            (local != remote).then_some(Report::Peer { local, remote })
        }
        "note" if !rest.is_empty() => Some(Report::Note(text(""))),
        "lost" => Some(Report::Lost(text("Windows closed the private network"))),
        "error" => Some(Report::Error(text("the private network could not be created"))),
        _ => None,
    }
}

fn private_address(text: &str) -> Option<Ipv4Addr> {
    text.parse::<Ipv4Addr>().ok().filter(Ipv4Addr::is_private)
}

// ─── Names, secrets and addresses ────────────────────────────────────────────

/// The same name every time for this device: a phone remembers its owner's
/// approval per network name. Derived from the device UUID as stored.
fn network_name(device_id: &str) -> String {
    let digest = Sha256::digest(device_id.as_bytes());
    let tail: String = digest.iter().take(5).map(|byte| format!("{byte:02x}")).collect();
    format!("DIRECT-DF-{tail}")
}

/// New for every session: 32 hex characters from the system's random source.
fn new_passphrase() -> String {
    uuid::Uuid::new_v4().simple().to_string()
}

/// Where the joiner says it listens. The other device chose this text, so
/// only a private IPv4 address is ever dialled.
fn joiner_address(text: &str) -> Result<Ipv4Addr, String> {
    private_address(text)
        .ok_or_else(|| "the other device's address is not on a private network".to_string())
}

/// This PC's address on the network `remote` joined, if Windows reported it.
fn local_end(pairs: &[(Ipv4Addr, Ipv4Addr)], remote: Ipv4Addr) -> Option<Ipv4Addr> {
    pairs.iter().rev().find(|pair| pair.1 == remote).map(|pair| pair.0)
}

/// Connects from `local` when it is known, so the connection can only leave
/// through the adapter that owns that address.
fn dial(local: Option<Ipv4Addr>, remote: SocketAddrV4, timeout: Duration) -> io::Result<TcpStream> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    if let Some(local) = local {
        socket.bind(&SocketAddrV4::new(local, 0).into())?;
    }
    socket.connect_timeout(&remote.into(), timeout)?;
    Ok(socket.into())
}

// ─── The helper process ──────────────────────────────────────────────────────

#[derive(Default)]
struct Seen {
    ready: bool,
    pairs: Vec<(Ipv4Addr, Ipv4Addr)>,
    /// The helper's `error` or `lost`: the network is not there.
    failed: Option<String>,
    /// The helper's output ended.
    ended: bool,
}

struct Helper {
    child: Child,
    /// Open for as long as the network should stay up.
    stdin: Option<ChildStdin>,
    seen: Arc<(Mutex<Seen>, Condvar)>,
}

impl Helper {
    fn start(command: &mut Command) -> Result<Self, String> {
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("the Wi-Fi Direct helper could not be started: {e}"))?;
        let stdin = child.stdin.take();
        let seen = Arc::new((Mutex::new(Seen::default()), Condvar::new()));
        if let Some(stdout) = child.stdout.take() {
            let seen = Arc::clone(&seen);
            // Ends by itself when the helper exits and its output closes.
            thread::spawn(move || {
                let (state, changed) = &*seen;
                for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                    let Some(report) = parse(&line) else { continue };
                    let Ok(mut seen) = state.lock() else { return };
                    match report {
                        Report::Ready => seen.ready = true,
                        Report::Peer { local, remote } => {
                            seen.pairs.retain(|pair| pair.1 != remote);
                            if seen.pairs.len() >= MAX_PAIRS {
                                seen.pairs.remove(0);
                            }
                            seen.pairs.push((local, remote));
                        }
                        Report::Note(note) => log::info!("[Link] Wi-Fi Direct helper: {note}"),
                        Report::Lost(reason) | Report::Error(reason) => {
                            seen.failed.get_or_insert(reason);
                        }
                    }
                    changed.notify_all();
                }
                if let Ok(mut seen) = state.lock() {
                    seen.ended = true;
                }
                changed.notify_all();
            });
        }
        Ok(Self { child, stdin, seen })
    }

    /// Waits up to `wait` for `done`, then hands the state to `read`.
    fn watch<T>(
        &self,
        wait: Duration,
        done: impl Fn(&Seen) -> bool,
        read: impl FnOnce(&Seen) -> T,
    ) -> Result<T, String> {
        const GONE: &str = "the Wi-Fi Direct helper stopped unexpectedly";
        let (state, changed) = &*self.seen;
        let seen = state.lock().map_err(|_| GONE)?;
        let (seen, _) = changed.wait_timeout_while(seen, wait, |seen| !done(seen)).map_err(|_| GONE)?;
        Ok(read(&seen))
    }

    /// Waits for the network to be advertised, keeping the session alive.
    fn await_ready(&self, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<(), String> {
        let deadline = Instant::now() + READY_TIMEOUT;
        loop {
            tick()?;
            let settled = self.watch(
                TICK_EVERY,
                |seen| seen.ready || seen.failed.is_some() || seen.ended,
                |seen| match &seen.failed {
                    Some(reason) => Some(Err(reason.clone())),
                    None if seen.ready => Some(Ok(())),
                    None if seen.ended => {
                        Some(Err("the Wi-Fi Direct helper closed before the network was up".into()))
                    }
                    None => None,
                },
            )?;
            if let Some(outcome) = settled {
                return outcome;
            }
            if Instant::now() >= deadline {
                return Err("Windows did not start the private network in time".into());
            }
        }
    }

    /// Checks `remote` against the device Windows saw join, waiting up to
    /// `patience` for Windows to report it. `Ok(Some(local))`: it is that
    /// device, reachable from `local`. `Ok(None)`: Windows named no device.
    fn route_to(&self, remote: Ipv4Addr, patience: Duration) -> Result<Option<Ipv4Addr>, String> {
        self.watch(
            patience,
            |seen| seen.failed.is_some() || seen.ended || local_end(&seen.pairs, remote).is_some(),
            |seen| match (&seen.failed, local_end(&seen.pairs, remote)) {
                (Some(reason), _) => Err(reason.clone()),
                (None, _) if seen.ended => Err("the private network is no longer up".into()),
                (None, Some(local)) => Ok(Some(local)),
                (None, None) if seen.pairs.is_empty() => Ok(None),
                (None, None) => {
                    Err("the other device's address is not the one that joined the private network".into())
                }
            },
        )?
    }

    /// Closing stdin asks the helper to take the network down and exit.
    fn stop(&mut self) {
        drop(self.stdin.take());
        let deadline = Instant::now() + EXIT_GRACE;
        loop {
            match self.child.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(25)),
                _ => break,
            }
        }
        if self.child.kill().is_ok() {
            let _ = self.child.wait();
        }
    }
}

// ─── The provider ────────────────────────────────────────────────────────────

/// Hosts the private network on a Windows PC. Never joins one.
pub struct WindowsHostLink {
    helper_path: PathBuf,
    ssid: String,
    helper: Option<Helper>,
    /// This link holds `HOSTING`.
    hosting: bool,
}

impl WindowsHostLink {
    /// `None` when this build has no helper, so nothing is offered.
    pub fn new(helper_path: PathBuf, device_id: &str) -> Option<Self> {
        helper_path.is_file().then(|| Self {
            helper_path,
            ssid: network_name(device_id),
            helper: None,
            hosting: false,
        })
    }

    /// No shell, no PATH lookup; both arguments are generated in this file.
    fn command(&self, passphrase: &str) -> Command {
        let mut command = Command::new(&self.helper_path);
        command.args(["host-link", &self.ssid, passphrase]);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        command
    }

    fn host_with(
        &mut self,
        mut command: Command,
        passphrase: String,
        tick: &mut dyn FnMut() -> Result<(), String>,
    ) -> Result<HostedLink, String> {
        self.close();
        tick()?;
        if HOSTING.swap(true, Ordering::AcqRel) {
            return Err("another transfer is using the direct link".into());
        }
        self.hosting = true;
        let started = Helper::start(&mut command).and_then(|helper| {
            let helper = self.helper.insert(helper);
            helper.await_ready(tick)
        });
        match started {
            Ok(()) => Ok(HostedLink { ssid: self.ssid.clone(), passphrase, listen_port: None }),
            Err(reason) => {
                self.close();
                Err(reason)
            }
        }
    }

    fn connect_within(&self, address: &str, port: u16, patience: Duration) -> Result<TcpStream, String> {
        let remote = joiner_address(address)?;
        if port == 0 {
            return Err("the other device gave no port to connect to".into());
        }
        let helper = self.helper.as_ref().ok_or("the private network is not up")?;
        let local = helper.route_to(remote, patience)?;
        if local.is_none() {
            // Windows did not say which device joined, so the address is only
            // the other device's word. Windows hands out 192.168.137.x on the
            // networks it hosts; anything else could be an unrelated machine
            // on another network this PC is connected to.
            if remote.octets()[..3] != [192, 168, 137] {
                return Err("the other device's address is not on the private network".into());
            }
            log::info!("[Link] Windows reported no address for the joined device; connecting without one");
        }
        dial(local, SocketAddrV4::new(remote, port), CONNECT_TIMEOUT)
            .map_err(|e| format!("could not connect over the private network: {e}"))
    }
}

impl DirectLink for WindowsHostLink {
    fn caps(&self) -> DirectCaps {
        DirectCaps { host_rank: 2, join: false }
    }

    fn host(&mut self, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<HostedLink, String> {
        let passphrase = new_passphrase();
        self.host_with(self.command(&passphrase), passphrase, tick)
    }

    fn join(
        &mut self,
        _offer: &DirectOffer,
        _tick: &mut dyn FnMut() -> Result<(), String>,
    ) -> Result<JoinedLink, String> {
        Err("this PC can create a direct link but not join one".into())
    }

    fn accept(&mut self, _wait: Duration) -> Result<Option<TcpStream>, String> {
        Err("this PC does not accept connections on a direct link".into())
    }

    fn connect(&mut self, address: Option<&str>, port: u16) -> Result<TcpStream, String> {
        let address = address.ok_or("the other device did not say where it listens")?;
        self.connect_within(address, port, PEER_TIMEOUT)
    }

    fn close(&mut self) {
        if let Some(mut helper) = self.helper.take() {
            helper.stop();
        }
        if std::mem::take(&mut self.hosting) {
            HOSTING.store(false, Ordering::Release);
        }
    }
}

impl Drop for WindowsHostLink {
    fn drop(&mut self) {
        self.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;

    fn ip(text: &str) -> Ipv4Addr {
        text.parse().unwrap()
    }

    #[test]
    fn helper_lines_are_read_strictly() {
        assert_eq!(parse("ready"), Some(Report::Ready));
        assert_eq!(
            parse("peer\t192.168.137.1\t192.168.137.24"),
            Some(Report::Peer { local: ip("192.168.137.1"), remote: ip("192.168.137.24") })
        );
        assert_eq!(parse("note\tthis PC's own Wi-Fi connection changed"), Some(Report::Note("this PC's own Wi-Fi connection changed".into())));
        assert_eq!(parse("lost\tradio busy"), Some(Report::Lost("radio busy".into())));
        assert_eq!(parse("error\tno adapter"), Some(Report::Error("no adapter".into())));
        // A failure without a reason is still a failure.
        assert!(matches!(parse("lost"), Some(Report::Lost(reason)) if !reason.is_empty()));
        assert!(matches!(parse("error\t"), Some(Report::Error(reason)) if !reason.is_empty()));
        assert_eq!(parse(&format!("error\t{}", "x".repeat(900))), Some(Report::Error("x".repeat(200))));

        for line in [
            "",
            "note",
            "verified\t900",
            "READY",
            "ready ",
            // The lab's own line carries credentials; it is not this protocol.
            "ready\tDIRECT-DF-12345abcde\t0123456789abcdef0123456789abcdef",
            "peer",
            "peer\t192.168.137.1",
            "peer\t192.168.137.1\t192.168.137.1",
            "peer\t192.168.137.1\t8.8.8.8",
            "peer\t203.0.113.7\t192.168.137.24",
            "peer\t192.168.137.1\t192.168.137.24\textra",
            "peer\t192.168.137.1\tfe80::1",
            "peer\t192.168.137.1\t 192.168.137.24",
        ] {
            assert_eq!(parse(line), None, "{line:?}");
        }
    }

    #[test]
    fn the_network_name_is_stable_per_device_and_the_passphrase_is_not() {
        let name = network_name("6f1d2c3b-4a59-4e68-9f70-8192a3b4c5d6");
        assert_eq!(name, "DIRECT-DF-8fdf77418e");
        assert_eq!(name, network_name("6f1d2c3b-4a59-4e68-9f70-8192a3b4c5d6"));
        let other = network_name("00000000-0000-4000-8000-000000000000");
        assert_ne!(name, other);
        let tail = other.strip_prefix("DIRECT-DF-").unwrap();
        assert!(tail.len() == 10 && tail.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()));

        let (first, second) = (new_passphrase(), new_passphrase());
        assert_ne!(first, second);
        for passphrase in [first, second] {
            assert!(passphrase.len() == 32 && passphrase.bytes().all(|b| b.is_ascii_hexdigit()));
        }
    }

    #[test]
    fn only_a_private_ipv4_address_is_ever_dialled() {
        for address in ["192.168.137.24", "10.0.0.7", "172.16.4.9", "172.31.255.254"] {
            assert_eq!(joiner_address(address), Ok(ip(address)));
        }
        for address in [
            "",
            "8.8.8.8",
            "127.0.0.1",
            "169.254.10.10",
            "172.32.0.1",
            "0.0.0.0",
            "255.255.255.255",
            "fe80::1",
            "::ffff:192.168.1.5",
            "phone.local",
            "192.168.1.5:80",
            " 192.168.1.5",
            "192.168.1",
            "192.168.001.005",
        ] {
            assert!(joiner_address(address).is_err(), "{address:?}");
        }
    }

    #[test]
    fn the_local_end_comes_from_the_pair_windows_reported() {
        let pairs = [
            (ip("192.168.137.1"), ip("192.168.137.24")),
            (ip("192.168.137.1"), ip("192.168.137.30")),
        ];
        assert_eq!(local_end(&pairs, ip("192.168.137.30")), Some(ip("192.168.137.1")));
        assert_eq!(local_end(&pairs, ip("192.168.137.99")), None);
        assert_eq!(local_end(&[], ip("192.168.137.24")), None);
    }

    #[test]
    fn the_connection_leaves_from_the_address_it_was_given() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let std::net::SocketAddr::V4(target) = listener.local_addr().unwrap() else { panic!("IPv4") };
        let mut out = dial(Some(Ipv4Addr::LOCALHOST), target, Duration::from_secs(5)).unwrap();
        assert_eq!(out.local_addr().unwrap().ip(), Ipv4Addr::LOCALHOST);
        let (mut accepted, _) = listener.accept().unwrap();
        // The engine needs an ordinary blocking socket back.
        out.write_all(b"ping").unwrap();
        let mut bytes = [0; 4];
        accepted.read_exact(&mut bytes).unwrap();
        assert_eq!(&bytes, b"ping");

        assert!(dial(None, target, Duration::from_secs(5)).is_ok());
        // An address this machine does not own cannot be used to leave from.
        assert!(dial(Some(ip("192.0.2.1")), target, Duration::from_secs(5)).is_err());
    }

    #[test]
    fn a_build_without_the_helper_offers_nothing() {
        let missing = std::env::temp_dir().join("dropflow-no-such-helper.exe");
        assert!(WindowsHostLink::new(missing, "id").is_none());
        assert!(WindowsHostLink::new(std::env::temp_dir(), "id").is_none());
    }

    #[cfg(unix)]
    mod with_a_stand_in_helper {
        use super::*;
        use std::sync::MutexGuard;

        /// `HOSTING` is one per process, so these tests take turns.
        fn turn() -> MutexGuard<'static, ()> {
            static TURN: Mutex<()> = Mutex::new(());
            TURN.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
        }

        fn link() -> WindowsHostLink {
            WindowsHostLink::new("/bin/sh".into(), "6f1d2c3b-4a59-4e68-9f70-8192a3b4c5d6").unwrap()
        }

        /// Hosts with a shell script standing in for the helper.
        fn host(link: &mut WindowsHostLink, script: &str) -> Result<HostedLink, String> {
            let mut command = Command::new("/bin/sh");
            command.args(["-c", script]);
            link.host_with(command, "0123456789abcdef0123456789abcdef".into(), &mut || Ok(()))
        }

        fn exited(link: &WindowsHostLink) -> bool {
            link.helper.is_none() && !link.hosting && !HOSTING.load(Ordering::Acquire)
        }

        #[test]
        fn the_helper_gets_the_device_name_and_a_fresh_passphrase() {
            let link = link();
            assert_eq!(link.caps(), DirectCaps { host_rank: 2, join: false });
            let command = link.command("0123456789abcdef0123456789abcdef");
            assert_eq!(command.get_program(), "/bin/sh");
            let args: Vec<_> = command.get_args().map(|arg| arg.to_str().unwrap()).collect();
            assert_eq!(args, ["host-link", "DIRECT-DF-8fdf77418e", "0123456789abcdef0123456789abcdef"]);
        }

        #[test]
        fn hosting_waits_for_ready_and_closing_stops_the_helper() {
            let _turn = turn();
            let mut link = link();
            let mut ticks = 0;
            let mut command = Command::new("/bin/sh");
            command.args(["-c", "sleep 0.5; printf 'note\\tstarting\\nready\\n'; read line; exit 0"]);
            let hosted = link
                .host_with(command, "0123456789abcdef0123456789abcdef".into(), &mut || {
                    ticks += 1;
                    Ok(())
                })
                .unwrap();
            assert_eq!(hosted.ssid, "DIRECT-DF-8fdf77418e");
            assert_eq!(hosted.passphrase, "0123456789abcdef0123456789abcdef");
            assert_eq!(hosted.listen_port, None);
            // Half a second of waiting, kept alive at least every 250 ms.
            assert!(ticks >= 3, "{ticks} ticks");

            let started = Instant::now();
            link.close();
            assert!(started.elapsed() < EXIT_GRACE, "the helper exits when its stdin closes");
            assert!(exited(&link));
            link.close();
        }

        #[test]
        fn a_helper_that_ignores_its_stdin_is_killed() {
            let _turn = turn();
            let mut link = link();
            host(&mut link, "printf 'ready\\n'; exec sleep 600").unwrap();
            let pid = link.helper.as_ref().unwrap().child.id();
            let started = Instant::now();
            drop(link);
            assert!(started.elapsed() < EXIT_GRACE + Duration::from_secs(2));
            assert!(!HOSTING.load(Ordering::Acquire));
            let alive = Command::new("/bin/kill").args(["-0", &pid.to_string()]).stderr(Stdio::null()).status().unwrap();
            assert!(!alive.success(), "the helper is gone");
        }

        #[test]
        fn a_failing_helper_leaves_nothing_behind() {
            let _turn = turn();
            let mut link = link();
            let refused = host(&mut link, "printf 'error\\tWi-Fi is off\\n'; exit 1");
            assert_eq!(refused.err().as_deref(), Some("Wi-Fi is off"));
            assert!(exited(&link));

            let silent = host(&mut link, "exit 3");
            assert_eq!(silent.err().as_deref(), Some("the Wi-Fi Direct helper closed before the network was up"));
            assert!(exited(&link));

            // Through the real entry point: /bin/sh is no helper and exits at once.
            assert!(link.host(&mut || Ok(())).is_err());
            assert!(exited(&link));

            let mut absent = link;
            absent.helper_path = "/no/such/helper".into();
            let unstarted = absent.host(&mut || Ok(()));
            assert!(unstarted.err().unwrap().starts_with("the Wi-Fi Direct helper could not be started"));
            assert!(exited(&absent));
        }

        #[test]
        fn hosting_stops_when_the_session_does() {
            let _turn = turn();
            let mut link = link();
            let mut command = Command::new("/bin/sh");
            command.args(["-c", "read line"]);
            let mut ticks = 0;
            let started = Instant::now();
            let stopped = link.host_with(command, "0123456789abcdef0123456789abcdef".into(), &mut || {
                ticks += 1;
                if ticks > 2 { Err("cancelled".into()) } else { Ok(()) }
            });
            assert_eq!(stopped.err().as_deref(), Some("cancelled"));
            assert!(started.elapsed() < Duration::from_secs(3));
            assert!(exited(&link));
        }

        #[test]
        fn one_network_at_a_time() {
            let _turn = turn();
            let (mut first, mut second) = (link(), link());
            host(&mut first, "printf 'ready\\n'; read line").unwrap();
            let busy = host(&mut second, "printf 'ready\\n'; read line");
            assert_eq!(busy.err().as_deref(), Some("another transfer is using the direct link"));
            // The refused link must not release the first one's claim.
            second.close();
            assert!(HOSTING.load(Ordering::Acquire));
            first.close();
            host(&mut second, "printf 'ready\\n'; read line").unwrap();
        }

        #[test]
        fn only_the_device_that_joined_is_dialled() {
            let _turn = turn();
            let mut link = link();
            let short = Duration::from_millis(300);
            assert_eq!(
                link.connect(Some("192.168.137.24"), 4000).err().as_deref(),
                Some("the private network is not up")
            );
            host(&mut link, "printf 'ready\\n'; sleep 0.3; printf 'peer\\t192.168.137.1\\t192.168.137.24\\n'; read line").unwrap();

            // Refused before anything is dialled, whatever Windows reported.
            assert!(link.connect(Some("8.8.8.8"), 4000).unwrap_err().contains("not on a private network"));
            assert!(link.connect(Some("192.168.137.24"), 0).unwrap_err().contains("no port"));
            assert!(link.connect(None, 4000).is_err());
            assert!(link.accept(short).is_err());
            let offer = DirectOffer { ssid: "DIRECT-x".into(), passphrase: "p".into(), token: "t".into(), host_port: None, leaving_ok: false };
            assert!(link.join(&offer, &mut || Ok(())).is_err());

            let helper = link.helper.as_ref().unwrap();
            // The pair arrives a moment after `ready`; the wait covers it.
            assert_eq!(helper.route_to(ip("192.168.137.24"), Duration::from_secs(5)), Ok(Some(ip("192.168.137.1"))));
            // Another private address is not the device that joined.
            let stranger = link.connect_within("192.168.1.50", 4000, short);
            assert!(stranger.unwrap_err().contains("not the one that joined"));
        }

        #[test]
        fn without_a_report_from_windows_the_address_is_still_private_only() {
            let _turn = turn();
            let mut link = link();
            host(&mut link, "printf 'ready\\n'; read line").unwrap();
            let helper = link.helper.as_ref().unwrap();
            assert_eq!(helper.route_to(ip("192.168.137.24"), Duration::from_millis(200)), Ok(None));
        }

        #[test]
        fn a_lost_network_is_not_dialled() {
            let _turn = turn();
            let mut link = link();
            host(&mut link, "printf 'ready\\n'; sleep 0.2; printf 'lost\\tradio busy\\nerror\\tradio busy\\n'; exit 1").unwrap();
            let started = Instant::now();
            let lost = link.connect(Some("192.168.137.24"), 4000);
            assert_eq!(lost.err().as_deref(), Some("radio busy"));
            assert!(started.elapsed() < PEER_TIMEOUT, "does not wait out the full patience");

            drop(link);
            let mut closed = self::link();
            host(&mut closed, "printf 'ready\\n'; sleep 0.2; exit 0").unwrap();
            let gone = closed.connect(Some("192.168.137.24"), 4000);
            assert_eq!(gone.err().as_deref(), Some("the private network is no longer up"));
        }
    }
}
