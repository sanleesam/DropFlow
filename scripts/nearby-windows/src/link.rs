//! What `host-link` promises the desktop app: the arguments it accepts and
//! the lines it prints on stdout. Portable, so the contract is tested anywhere.
//!
//! ```text
//! ready                          the network is advertising
//! peer\t<local ipv4>\t<remote>   a device joined; both ends' private addresses
//! note\t<text>                   worth logging, changes nothing
//! lost\t<reason>                 Windows took the network down
//! error\t<reason>                failed; the process exits non-zero
//! ```
//!
//! `status` prints one more line for the app, among its text for people:
//! `direct\t<finding>`, see `Readiness`.
use std::net::Ipv4Addr;
#[cfg(windows)]
use std::time::Duration;

/// The network never outlives this, even if the app never closes stdin.
#[cfg(windows)]
pub const LIFETIME: Duration = Duration::from_secs(30 * 60);

/// A Wi-Fi Direct network name and a WPA2 passphrase, as plain visible ASCII.
pub fn check_arguments(ssid: &str, passphrase: &str) -> Result<(), &'static str> {
    let plain = |text: &str| text.bytes().all(|byte| byte.is_ascii_graphic());
    if !ssid.starts_with("DIRECT-") || !(8..=32).contains(&ssid.len()) || !plain(ssid) {
        return Err("the network name must start with DIRECT- and be 8 to 32 plain characters");
    }
    if !(8..=63).contains(&passphrase.len()) || !plain(passphrase) {
        return Err("the passphrase must be 8 to 63 plain characters");
    }
    Ok(())
}

/// The two ends of a joined device's connection, when both are distinct
/// private IPv4 addresses. Anything else is not a link this helper created.
pub fn private_pair(local: &str, remote: &str) -> Option<(Ipv4Addr, Ipv4Addr)> {
    let local: Ipv4Addr = local.parse().ok()?;
    let remote: Ipv4Addr = remote.parse().ok()?;
    (local.is_private() && remote.is_private() && local != remote).then_some((local, remote))
}

/// Whether this PC could host, as far as can be told without starting
/// anything. The app shows it in Settings.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Readiness {
    Ready,
    NoWifi,
    WifiOff,
    NoWifiDirect,
}

impl Readiness {
    pub fn line(self) -> &'static str {
        match self {
            Readiness::Ready => "direct\tok",
            Readiness::NoWifi => "direct\tno-wifi",
            Readiness::WifiOff => "direct\twifi-off",
            Readiness::NoWifiDirect => "direct\tno-wifi-direct",
        }
    }
}

/// Windows sets up a "Microsoft Wi-Fi Direct Virtual Adapter" for a Wi-Fi
/// driver that supports Wi-Fi Direct; this recognises its description.
pub fn names_wifi_direct(adapter_description: &str) -> bool {
    let name = adapter_description.to_lowercase();
    name.contains("wi-fi direct") || name.contains("wifi direct")
}

pub enum Event<'a> {
    Ready,
    Peer(Ipv4Addr, Ipv4Addr),
    Note(&'a str),
    Lost(&'a str),
    Error(&'a str),
}

impl Event<'_> {
    /// One line, without its newline. Free text can never add a field or a line.
    pub fn line(&self) -> String {
        let text = |text: &str| text.replace(['\r', '\n', '\t'], " ");
        match self {
            Event::Ready => "ready".into(),
            Event::Peer(local, remote) => format!("peer\t{local}\t{remote}"),
            Event::Note(note) => format!("note\t{}", text(note)),
            Event::Lost(reason) => format!("lost\t{}", text(reason)),
            Event::Error(reason) => format!("error\t{}", text(reason)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_wifi_direct_name_and_a_wpa2_passphrase_are_accepted() {
        let passphrase = "0123456789abcdef0123456789abcdef";
        assert_eq!(check_arguments("DIRECT-DF-0123456789", passphrase), Ok(()));
        assert_eq!(check_arguments("DIRECT-x", "12345678"), Ok(()));
        for ssid in ["", "DIRECT-", "HomeWifi", "DIRECT-DF 0123456789", "DIRECT-DF-\u{e9}", &format!("DIRECT-{}", "a".repeat(26))] {
            assert!(check_arguments(ssid, passphrase).is_err(), "{ssid:?}");
        }
        for passphrase in ["", "1234567", "has a space", "tab\there", &"a".repeat(64)] {
            assert!(check_arguments("DIRECT-DF-0123456789", passphrase).is_err(), "{passphrase:?}");
        }
    }

    #[test]
    fn a_peer_is_reported_only_with_two_distinct_private_addresses() {
        let pair = Some((Ipv4Addr::new(192, 168, 137, 1), Ipv4Addr::new(192, 168, 137, 24)));
        assert_eq!(private_pair("192.168.137.1", "192.168.137.24"), pair);
        for (local, remote) in [
            ("192.168.137.1", "192.168.137.1"),
            ("192.168.137.1", "8.8.8.8"),
            ("203.0.113.5", "192.168.137.24"),
            ("192.168.137.1", "fe80::1"),
            ("192.168.137.1", "phone.local"),
            ("", ""),
        ] {
            assert_eq!(private_pair(local, remote), None, "{local} {remote}");
        }
    }

    #[test]
    fn the_status_line_for_the_app_has_one_fixed_finding() {
        use Readiness::*;
        let lines: Vec<_> = [Ready, NoWifi, WifiOff, NoWifiDirect].map(Readiness::line).into();
        assert_eq!(lines, ["direct\tok", "direct\tno-wifi", "direct\twifi-off", "direct\tno-wifi-direct"]);
    }

    #[test]
    fn the_wifi_direct_adapter_is_recognised_by_its_description() {
        for name in ["Microsoft Wi-Fi Direct Virtual Adapter", "Microsoft Wi-Fi Direct Virtual Adapter #2", "microsoft wifi direct virtual adapter"] {
            assert!(names_wifi_direct(name), "{name}");
        }
        for name in ["Intel(R) Wi-Fi 6 AX201 160MHz", "Microsoft Hosted Network Virtual Adapter", "Realtek PCIe GbE Family Controller", ""] {
            assert!(!names_wifi_direct(name), "{name}");
        }
    }

    #[test]
    fn every_event_is_one_line_with_fixed_fields() {
        let (local, remote) = private_pair("192.168.137.1", "192.168.137.24").unwrap();
        assert_eq!(Event::Ready.line(), "ready");
        assert_eq!(Event::Peer(local, remote).line(), "peer\t192.168.137.1\t192.168.137.24");
        assert_eq!(Event::Note("Wi-Fi\tchanged").line(), "note\tWi-Fi changed");
        assert_eq!(Event::Lost("radio\r\nbusy").line(), "lost\tradio  busy");
        assert_eq!(Event::Error("no adapter\n").line(), "error\tno adapter ");
    }
}
