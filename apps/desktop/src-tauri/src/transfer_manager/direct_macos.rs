//! macOS side of a direct link: join the other device's private Wi-Fi network.
//!
//! macOS gives apps no second Wi-Fi connection, so joining means this Mac
//! leaves its own network until the transfer ends. That is why this exists
//! only behind the Direct Link setting, which says so. The Mac returns to its
//! usual network by itself once the private one is gone; `close` helps it
//! along and removes the temporary network from the saved list where macOS
//! allows that without an administrator.
use std::io::Read;
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use super::protocol::{DirectCaps, DirectOffer};
use super::session::{DirectLink, HostedLink, JoinedLink};

const NETWORKSETUP: &str = "/usr/sbin/networksetup";
const IPCONFIG: &str = "/usr/sbin/ipconfig";
const JOIN_TIMEOUT: Duration = Duration::from_secs(25);
const ADDRESS_TIMEOUT: Duration = Duration::from_secs(20);
const POLL: Duration = Duration::from_millis(200);

/// The Wi-Fi interface name (for example `en0`) from
/// `networksetup -listallhardwareports`.
fn parse_wifi_device(listing: &str) -> Option<String> {
    let mut lines = listing.lines();
    while let Some(line) = lines.next() {
        if line.trim() == "Hardware Port: Wi-Fi" {
            return lines
                .next()?
                .trim()
                .strip_prefix("Device: ")
                .map(str::to_string)
                .filter(|device| !device.is_empty() && device.chars().all(|c| c.is_ascii_alphanumeric()));
        }
    }
    None
}

/// Only networks that look like a direct link are ever joined: the name comes
/// from the other device, and this Mac is about to trust it with its Wi-Fi.
fn acceptable_network(ssid: &str, passphrase: &str) -> Result<(), String> {
    let plain = |text: &str| text.chars().all(|c| c.is_ascii_graphic());
    if !ssid.starts_with("DIRECT-") || ssid.len() > 32 || !plain(ssid) {
        return Err("the other device offered a network that is not a direct link".into());
    }
    if !(8..=63).contains(&passphrase.len()) || !plain(passphrase) {
        return Err("the other device offered an unusable network password".into());
    }
    Ok(())
}

/// `networksetup` reports failures on standard output and still exits 0.
fn join_failure(output: &str) -> Option<String> {
    let text = output.trim();
    if text.is_empty() {
        return None;
    }
    Some(if text.contains("Could not find network") {
        "not found".into()
    } else {
        text.lines().next().unwrap_or(text).chars().take(160).collect()
    })
}

fn private_address(text: &str) -> Option<Ipv4Addr> {
    text.trim().parse::<Ipv4Addr>().ok().filter(Ipv4Addr::is_private)
}

fn run(program: &str, args: &[&str], patience: Duration, tick: &mut dyn FnMut() -> Result<(), String>) -> Result<String, String> {
    let mut child = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("could not run {program}: {e}"))?;
    let deadline = Instant::now() + patience;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {}
            Err(error) => return Err(error.to_string()),
        }
        let stop = tick().err().or_else(|| (Instant::now() >= deadline).then(|| "timed out".to_string()));
        if let Some(reason) = stop {
            let _ = child.kill();
            let _ = child.wait();
            return Err(reason);
        }
        std::thread::sleep(POLL);
    }
    let mut output = String::new();
    if let Some(mut stdout) = child.stdout.take() {
        let _ = stdout.read_to_string(&mut output);
    }
    Ok(output)
}

fn quiet(program: &str, args: &[&str]) -> String {
    run(program, args, Duration::from_secs(8), &mut || Ok(())).unwrap_or_default()
}

pub struct MacJoinLink {
    device: String,
    listener: Option<TcpListener>,
    /// The private network this Mac joined, and its address there.
    joined: Option<(String, Ipv4Addr)>,
}

impl MacJoinLink {
    /// `None` when this Mac has no Wi-Fi interface.
    pub fn new() -> Option<Self> {
        let device = parse_wifi_device(&quiet(NETWORKSETUP, &["-listallhardwareports"]))?;
        Some(Self { device, listener: None, joined: None })
    }

    fn address(&self) -> Option<Ipv4Addr> {
        private_address(&quiet(IPCONFIG, &["getifaddr", &self.device]))
    }
}

impl DirectLink for MacJoinLink {
    fn caps(&self) -> DirectCaps {
        DirectCaps { host_rank: 0, join: true }
    }

    fn leaves_network(&self) -> bool {
        true
    }

    fn listen(&mut self) -> Result<u16, String> {
        // Every interface: the private network's address does not exist yet.
        let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).map_err(|e| e.to_string())?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        self.listener = Some(listener);
        Ok(port)
    }

    fn join(
        &mut self,
        offer: &DirectOffer,
        tick: &mut dyn FnMut() -> Result<(), String>,
    ) -> Result<JoinedLink, String> {
        acceptable_network(&offer.ssid, &offer.passphrase)?;
        let before = self.address();
        // The network was created seconds ago and may not be visible yet.
        let mut attempt = 0;
        loop {
            attempt += 1;
            let output = run(
                NETWORKSETUP,
                &["-setairportnetwork", &self.device, &offer.ssid, &offer.passphrase],
                JOIN_TIMEOUT,
                tick,
            )?;
            match join_failure(&output) {
                None => break,
                Some(reason) if reason == "not found" && attempt < 4 => {
                    for _ in 0..10 {
                        tick()?;
                        std::thread::sleep(POLL);
                    }
                }
                Some(reason) => return Err(format!("this Mac could not join the direct link ({reason})")),
            }
        }
        // Joined from here on, whatever happens: `close` must undo it.
        self.joined = Some((offer.ssid.clone(), Ipv4Addr::UNSPECIFIED));
        let deadline = Instant::now() + ADDRESS_TIMEOUT;
        let address = loop {
            tick()?;
            match self.address() {
                Some(address) if Some(address) != before => break address,
                _ if Instant::now() >= deadline => {
                    return Err("this Mac joined the direct link but got no address on it".into())
                }
                _ => std::thread::sleep(POLL),
            }
        };
        self.joined = Some((offer.ssid.clone(), address));
        let port = self
            .listener
            .as_ref()
            .and_then(|listener| listener.local_addr().ok())
            .map_or(0, |local| local.port());
        Ok(JoinedLink { address: address.to_string(), port })
    }

    fn accept(&mut self, wait: Duration) -> Result<Option<TcpStream>, String> {
        let listener = self.listener.as_ref().ok_or("this Mac is not listening")?;
        let deadline = Instant::now() + wait;
        loop {
            match listener.accept() {
                Ok((socket, _)) => {
                    socket.set_nonblocking(false).map_err(|e| e.to_string())?;
                    return Ok(Some(socket));
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    if Instant::now() >= deadline {
                        return Ok(None);
                    }
                    std::thread::sleep(Duration::from_millis(20));
                }
                Err(error) => return Err(error.to_string()),
            }
        }
    }

    fn connect(&mut self, _address: Option<&str>, _port: u16) -> Result<TcpStream, String> {
        Err("this Mac waits for the other device to connect".into())
    }

    fn host(&mut self, _tick: &mut dyn FnMut() -> Result<(), String>) -> Result<HostedLink, String> {
        Err("a Mac can join a direct link but not create one".into())
    }

    fn close(&mut self) {
        self.listener = None;
        let Some((ssid, address)) = self.joined.take() else { return };
        // Forget the temporary network where macOS allows it without an
        // administrator; if it does not, the entry is harmless (its password
        // changes every session).
        let _ = quiet(NETWORKSETUP, &["-removepreferredwirelessnetwork", &self.device, &ssid]);
        // The other device takes its network down when the session ends and
        // this Mac then rejoins its own. If it is still on the private one
        // after a moment, switch Wi-Fi off and on so macOS picks its usual
        // network again.
        std::thread::sleep(Duration::from_secs(3));
        if address != Ipv4Addr::UNSPECIFIED && self.address() == Some(address) {
            log::info!("[Link] Still on the direct link's network; restarting Wi-Fi to return to the usual one");
            let _ = quiet(NETWORKSETUP, &["-setairportpower", &self.device, "off"]);
            let _ = quiet(NETWORKSETUP, &["-setairportpower", &self.device, "on"]);
        }
    }
}

impl Drop for MacJoinLink {
    fn drop(&mut self) {
        self.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_wifi_interface_and_nothing_else() {
        let listing = "\nHardware Port: Ethernet Adapter (en4)\nDevice: en4\nEthernet Address: aa\n\nHardware Port: Wi-Fi\nDevice: en0\nEthernet Address: bb\n\nHardware Port: Thunderbolt Bridge\nDevice: bridge0\n";
        assert_eq!(parse_wifi_device(listing).as_deref(), Some("en0"));
        assert_eq!(parse_wifi_device("Hardware Port: Ethernet\nDevice: en4\n"), None);
        assert_eq!(parse_wifi_device("Hardware Port: Wi-Fi\nDevice: en0; rm -rf\n"), None);
    }

    #[test]
    fn only_direct_link_networks_are_joined() {
        assert!(acceptable_network("DIRECT-DF-0123456789", "0123456789abcdef0123456789abcdef").is_ok());
        for (ssid, passphrase) in [
            ("HomeWiFi", "0123456789abcdef"),            // someone's real network
            ("DIRECT-DF with space", "0123456789abcdef"),
            ("-setairportpower", "0123456789abcdef"),     // must never look like an option
            ("DIRECT-DF-0123456789", "short"),
            ("DIRECT-DF-0123456789", "has space in it"),
            ("DIRECT-DF-0123456789-far-too-long-for-an-ssid", "0123456789abcdef"),
        ] {
            assert!(acceptable_network(ssid, passphrase).is_err(), "{ssid} / {passphrase}");
        }
    }

    #[test]
    fn join_output_is_read_the_way_networksetup_writes_it() {
        assert_eq!(join_failure(""), None);
        assert_eq!(join_failure("\n"), None);
        assert_eq!(join_failure("Could not find network DIRECT-DF-1.\n").as_deref(), Some("not found"));
        assert!(join_failure("Failed to join network DIRECT-DF-1.\nError: -3900  The operation couldn't be completed.").unwrap().starts_with("Failed to join"));
    }

    #[test]
    fn only_private_addresses_count_as_joined() {
        assert_eq!(private_address("192.168.137.24\n"), Some(Ipv4Addr::new(192, 168, 137, 24)));
        assert_eq!(private_address("8.8.8.8"), None);
        assert_eq!(private_address(""), None);
    }

    #[test]
    fn a_command_is_stopped_when_the_transfer_is_cancelled_or_takes_too_long() {
        let mut ticks = 0;
        let cancelled = run("/bin/sleep", &["30"], Duration::from_secs(30), &mut || {
            ticks += 1;
            if ticks > 2 { Err("cancelled".into()) } else { Ok(()) }
        });
        assert_eq!(cancelled.unwrap_err(), "cancelled");
        assert_eq!(run("/bin/sleep", &["30"], Duration::from_millis(300), &mut || Ok(())).unwrap_err(), "timed out");
        assert_eq!(run("/bin/echo", &["hello"], Duration::from_secs(5), &mut || Ok(())).unwrap(), "hello\n");
    }

    #[test]
    fn listening_starts_before_any_network_is_joined() {
        let mut link = MacJoinLink { device: "en0".into(), listener: None, joined: None };
        let port = link.listen().unwrap();
        assert!(port > 0);
        assert!(link.accept(Duration::from_millis(50)).unwrap().is_none());
        let _client = TcpStream::connect(("127.0.0.1", port)).unwrap();
        assert!(link.accept(Duration::from_secs(2)).unwrap().is_some());
        link.close(); // nothing was joined: must not touch Wi-Fi
        assert!(link.accept(Duration::from_millis(10)).is_err());
    }
}
