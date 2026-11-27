//! `host-link <ssid> <passphrase>`: keeps a Wi-Fi Direct network up for the
//! desktop app and reports who joins. The app owns every socket; nothing is
//! sent or listened for here. The PC's own Wi-Fi is only ever looked at.
//! The lines printed are described in `link`.
use crate::link::{self, Event};
use crate::windows_host::{
    internet_adapter, legacy_group, wait_for, Result, Wlan, TICK,
};
use std::io::{self, Write};
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};
use windows::core::GUID;
use windows::Devices::WiFiDirect::{
    WiFiDirectAdvertisementPublisher, WiFiDirectAdvertisementPublisherStatus,
    WiFiDirectConnectionListener, WiFiDirectConnectionRequest,
    WiFiDirectConnectionRequestedEventArgs, WiFiDirectConnectionStatus, WiFiDirectDevice,
};
use windows::Foundation::TypedEventHandler;

const ABORTED: &str = "Windows aborted Wi-Fi Direct; check adapter/driver support or a busy radio";
/// A transfer has one peer; the rest is room for a phone that reconnects.
const MAX_PEERS: usize = 4;

/// A failed write means the app is gone, which ends the session.
fn say(event: Event<'_>) -> io::Result<()> {
    let mut out = io::stdout().lock();
    writeln!(out, "{}", event.line())?;
    out.flush()
}

/// The advertised network. Dropping it takes the network down.
struct Group {
    publisher: WiFiDirectAdvertisementPublisher,
    listener: WiFiDirectConnectionListener,
    token: i64,
    /// Windows drops a device's connection with its object, so they live here.
    peers: Vec<WiFiDirectDevice>,
}

impl Drop for Group {
    fn drop(&mut self) {
        let _ = self.listener.RemoveConnectionRequested(self.token);
        for peer in &self.peers {
            let _ = peer.Close();
        }
        let _ = self.publisher.Stop();
    }
}

impl Group {
    fn aborted(&self) -> Result<bool> {
        Ok(self.publisher.Status()? == WiFiDirectAdvertisementPublisherStatus::Aborted)
    }

    /// Lets a device in and returns its address pair once Windows has one.
    fn admit(
        &mut self,
        request: WiFiDirectConnectionRequest,
        stop: &dyn Fn() -> bool,
    ) -> Result<Option<(Ipv4Addr, Ipv4Addr)>> {
        self.peers.retain(|peer| {
            peer.ConnectionStatus().is_ok_and(|status| status == WiFiDirectConnectionStatus::Connected)
        });
        if self.peers.len() >= MAX_PEERS {
            let _ = request.Close();
            return Err("too many devices are on the network; one was turned away".into());
        }
        let stopping = || -> Result<()> {
            if stop() {
                return Err("stopping".into());
            }
            Ok(())
        };
        let device = wait_for(
            WiFiDirectDevice::FromIdAsync(&request.DeviceInformation()?.Id()?)?,
            Instant::now() + Duration::from_secs(20),
            stopping,
        )?;
        // The addresses can trail the connection by a moment.
        let patience = Instant::now() + Duration::from_secs(5);
        let pair = loop {
            let pair = address_pair(&device);
            if pair.is_some() || stop() || Instant::now() >= patience {
                break pair;
            }
            thread::sleep(TICK);
        };
        self.peers.push(device);
        Ok(pair)
    }
}

fn address_pair(device: &WiFiDirectDevice) -> Option<(Ipv4Addr, Ipv4Addr)> {
    let pairs = device.GetConnectionEndpointPairs().ok()?;
    (0..pairs.Size().ok()?).find_map(|index| {
        let pair = pairs.GetAt(index).ok()?;
        link::private_pair(
            &pair.LocalHostName().ok()?.CanonicalName().ok()?.to_string(),
            &pair.RemoteHostName().ok()?.CanonicalName().ok()?.to_string(),
        )
    })
}

/// The PC's own connections, watched only so a change shows up in the app's
/// log. Unlike the lab, a change never takes the network down.
struct OwnNetwork<'a> {
    wlan: &'a Wlan,
    wifi: Option<Vec<GUID>>,
    internet: Option<Option<GUID>>,
    next: Instant,
}

impl<'a> OwnNetwork<'a> {
    fn new(wlan: &'a Wlan) -> Self {
        Self {
            wlan,
            wifi: wlan.snapshot().ok().map(|(_, connected)| connected),
            internet: internet_adapter().ok(),
            next: Instant::now() + Duration::from_secs(1),
        }
    }

    fn changes(&mut self) -> Vec<&'static str> {
        let mut notes = Vec::new();
        if Instant::now() < self.next {
            return notes;
        }
        self.next = Instant::now() + Duration::from_secs(1);
        if let Ok((_, connected)) = self.wlan.snapshot() {
            if self.wifi.as_ref().is_some_and(|before| *before != connected) {
                notes.push("this PC's own Wi-Fi connection changed");
            }
            self.wifi = Some(connected);
        }
        if let Ok(adapter) = internet_adapter() {
            if self.internet.is_some_and(|before| before != adapter) {
                notes.push("this PC's internet connection changed");
            }
            self.internet = Some(adapter);
        }
        notes
    }
}

pub fn host(wlan: &Wlan, ssid: &str, passphrase: &str, cancelled: &AtomicBool) -> Result<()> {
    let stop = || cancelled.load(Ordering::Relaxed);
    let deadline = Instant::now() + link::LIFETIME;
    let publisher = legacy_group(ssid, passphrase)?;
    let listener = WiFiDirectConnectionListener::new()?;
    let (requests, joining) = mpsc::sync_channel::<WiFiDirectConnectionRequest>(MAX_PEERS);
    let handler = TypedEventHandler::<
        WiFiDirectConnectionListener,
        WiFiDirectConnectionRequestedEventArgs,
    >::new(move |_, args| {
        if let Some(args) = args.as_ref() {
            if let Ok(request) = args.GetConnectionRequest() {
                if let Err(
                    mpsc::TrySendError::Full(extra) | mpsc::TrySendError::Disconnected(extra),
                ) = requests.try_send(request)
                {
                    let _ = extra.Close();
                }
            }
        }
        Ok(())
    });
    let token = listener.ConnectionRequested(&handler)?;
    let mut group = Group { publisher, listener, token, peers: Vec::new() };

    group.publisher.Start()?;
    let startup = Instant::now() + Duration::from_secs(10);
    while group.publisher.Status()? != WiFiDirectAdvertisementPublisherStatus::Started {
        if stop() {
            return Ok(());
        }
        if group.aborted()? {
            return Err(ABORTED.into());
        }
        if Instant::now() >= startup {
            return Err("Windows did not start a Wi-Fi Direct group".into());
        }
        thread::sleep(TICK);
    }
    say(Event::Ready)?;

    let mut own = OwnNetwork::new(wlan);
    while !stop() && Instant::now() < deadline {
        if group.aborted()? {
            say(Event::Lost(ABORTED))?;
            return Err(ABORTED.into());
        }
        for note in own.changes() {
            say(Event::Note(note))?;
        }
        match joining.recv_timeout(TICK) {
            Ok(request) => match group.admit(request, &stop) {
                Ok(Some((local, remote))) => say(Event::Peer(local, remote))?,
                Ok(None) => say(Event::Note(
                    "a device joined, but Windows reported no private IPv4 addresses for it",
                ))?,
                Err(_) if stop() => {}
                Err(error) => say(Event::Note(&format!("a device could not join: {error}")))?,
            },
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}
