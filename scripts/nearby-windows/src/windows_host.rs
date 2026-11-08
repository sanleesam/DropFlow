use crate::wire;
use std::error::Error;
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};
use uuid::Uuid;
use windows::core::{GUID, HSTRING};
use windows::Devices::Bluetooth::BluetoothAdapter;
use windows::Devices::Radios::{Radio, RadioKind, RadioState};
use windows::Devices::WiFiDirect::{
    WiFiDirectAdvertisementListenStateDiscoverability, WiFiDirectAdvertisementPublisher,
    WiFiDirectAdvertisementPublisherStatus, WiFiDirectConnectionListener,
    WiFiDirectConnectionRequest, WiFiDirectConnectionRequestedEventArgs, WiFiDirectDevice,
};
use windows::Foundation::TypedEventHandler;
use windows::Networking::Connectivity::{NetworkConnectivityLevel, NetworkInformation};
use windows::Security::Credentials::PasswordCredential;
use windows::Win32::Foundation::HANDLE;
use windows::Win32::NetworkManagement::WiFi::{
    wlan_interface_state_connected, WlanCloseHandle, WlanEnumInterfaces, WlanFreeMemory,
    WlanOpenHandle, WLAN_INTERFACE_INFO, WLAN_INTERFACE_INFO_LIST,
};
use windows::Win32::System::WinRT::{RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED};
use windows_future::{AsyncStatus, IAsyncOperation};

type Result<T> = std::result::Result<T, Box<dyn Error>>;
const TICK: Duration = Duration::from_millis(100);

struct Runtime;
impl Drop for Runtime {
    fn drop(&mut self) {
        unsafe { RoUninitialize() };
    }
}

struct Wlan(HANDLE);
impl Drop for Wlan {
    fn drop(&mut self) {
        unsafe { WlanCloseHandle(self.0, None) };
    }
}

struct Interfaces(*mut WLAN_INTERFACE_INFO_LIST);
impl Drop for Interfaces {
    fn drop(&mut self) {
        unsafe { WlanFreeMemory(self.0.cast()) };
    }
}

fn win32(code: u32) -> io::Result<()> {
    if code == 0 {
        Ok(())
    } else {
        Err(io::Error::from_raw_os_error(code as i32))
    }
}

impl Wlan {
    fn open() -> Result<Self> {
        let mut version = 0;
        let mut handle = HANDLE::default();
        win32(unsafe { WlanOpenHandle(2, None, &mut version, &mut handle) })?;
        Ok(Self(handle))
    }

    fn snapshot(&self) -> Result<(bool, Vec<GUID>)> {
        let mut list = std::ptr::null_mut();
        win32(unsafe { WlanEnumInterfaces(self.0, None, &mut list) })?;
        if list.is_null() {
            return Err("Windows did not return a Wi-Fi interface list".into());
        }
        let _list = Interfaces(list);
        // Native WLAN allocates this trailing array, dwNumberOfItems elements long.
        let entries = unsafe {
            std::slice::from_raw_parts(
                std::ptr::addr_of!((*list).InterfaceInfo).cast::<WLAN_INTERFACE_INFO>(),
                (*list).dwNumberOfItems as usize,
            )
        };
        Ok((
            !entries.is_empty(),
            entries
                .iter()
                .filter(|entry| entry.isState == wlan_interface_state_connected)
                .map(|entry| entry.InterfaceGuid)
                .collect(),
        ))
    }
}

fn wait_for<T: windows::core::RuntimeType>(
    operation: IAsyncOperation<T>,
    deadline: Instant,
    mut check: impl FnMut() -> Result<()>,
) -> Result<T> {
    let result = (|| {
        while operation.Status()? == AsyncStatus::Started {
            check()?;
            if Instant::now() >= deadline {
                return Err("Windows radio operation timed out".into());
            }
            thread::sleep(TICK);
        }
        Ok(operation.GetResults()?)
    })();
    if result.is_err() {
        let _ = operation.Cancel();
    }
    result
}

fn check_radios(wlan: &Wlan) -> Result<()> {
    if !wlan.snapshot()?.0 {
        return Err("no usable Wi-Fi adapter was found".into());
    }
    let deadline = Instant::now() + Duration::from_secs(5);
    let radios = wait_for(Radio::GetRadiosAsync()?, deadline, || Ok(()))?;
    let mut wifi_on = false;
    for index in 0..radios.Size()? {
        let radio = radios.GetAt(index)?;
        wifi_on |= radio.Kind()? == RadioKind::WiFi && radio.State()? == RadioState::On;
    }
    if !wifi_on {
        return Err("Wi-Fi is off or its radio is unavailable".into());
    }
    // Windows can return no default Bluetooth adapter, surfaced as a null
    // WinRT result/error. Both absence and an indeterminate result fail closed.
    let adapter = wait_for(BluetoothAdapter::GetDefaultAsync()?, deadline, || Ok(()))
        .map_err(|err| format!("no usable Bluetooth adapter: {err}"))?;
    if !adapter.IsLowEnergySupported()? {
        return Err("the Bluetooth adapter does not support BLE".into());
    }
    let bluetooth = wait_for(adapter.GetRadioAsync()?, deadline, || Ok(()))?;
    if bluetooth.State()? != RadioState::On {
        return Err("Bluetooth is off or unavailable".into());
    }
    Ok(())
}

fn internet_adapter() -> Result<Option<GUID>> {
    let profile = NetworkInformation::GetInternetConnectionProfile();
    // WinRT projects a successful null interface as E_POINTER.
    let profile = match profile {
        Ok(profile) => profile,
        Err(error) if error.code().0 == 0x80004003u32 as i32 => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    if profile.GetNetworkConnectivityLevel()? != NetworkConnectivityLevel::InternetAccess {
        return Ok(None);
    }
    Ok(Some(profile.NetworkAdapter()?.NetworkAdapterId()?))
}

struct Session<'a> {
    publisher: WiFiDirectAdvertisementPublisher,
    listener: WiFiDirectConnectionListener,
    token: i64,
    wlan: &'a Wlan,
    primary_wifi: Vec<GUID>,
    primary_internet: Option<GUID>,
    deadline: Instant,
}

impl Drop for Session<'_> {
    fn drop(&mut self) {
        let _ = self.listener.RemoveConnectionRequested(self.token);
        let _ = self.publisher.Stop();
    }
}

impl Session<'_> {
    fn check(&self) -> Result<()> {
        if Instant::now() >= self.deadline {
            return Err("Nearby Link session timed out".into());
        }
        if self.publisher.Status()? == WiFiDirectAdvertisementPublisherStatus::Aborted {
            return Err(
                "Windows aborted Wi-Fi Direct; check adapter/driver support or a busy radio".into(),
            );
        }
        if !self.primary_wifi.is_empty() {
            let (_, current_wifi) = self.wlan.snapshot()?;
            if !self.primary_wifi.iter().all(|id| current_wifi.contains(id)) {
                return Err(
                    "Existing Windows Wi-Fi association was lost; stopping the probe".into(),
                );
            }
        }
        if self.primary_internet.is_some() && self.primary_internet != internet_adapter()? {
            return Err("Windows Internet connection changed; stopping the probe".into());
        }
        Ok(())
    }
}

struct GuardedSocket<'a> {
    stream: TcpStream,
    session: &'a Session<'a>,
    next_check: Instant,
}

impl GuardedSocket<'_> {
    fn check(&mut self) -> io::Result<()> {
        if Instant::now() >= self.next_check {
            self.session
                .check()
                .map_err(|err| io::Error::other(err.to_string()))?;
            self.next_check = Instant::now() + Duration::from_millis(250);
        }
        Ok(())
    }
}

impl Read for GuardedSocket<'_> {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        self.check()?;
        self.stream.read(bytes)
    }
}

impl Write for GuardedSocket<'_> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.check()?;
        self.stream.write(bytes)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.stream.flush()
    }
}

fn host(wlan: &Wlan) -> Result<()> {
    let primary_wifi = wlan.snapshot()?.1;
    let primary_internet = internet_adapter()?;
    let publisher = WiFiDirectAdvertisementPublisher::new()?;
    let advertisement = publisher.Advertisement()?;
    advertisement.SetIsAutonomousGroupOwnerEnabled(true)?;
    advertisement
        .SetListenStateDiscoverability(WiFiDirectAdvertisementListenStateDiscoverability::Normal)?;
    let settings = advertisement.LegacySettings()?;
    settings.SetIsEnabled(true)?;
    let ssid = format!("DIRECT-DF-{}", &Uuid::new_v4().simple().to_string()[..10]);
    let passphrase = Uuid::new_v4().simple().to_string();
    let credential = PasswordCredential::new()?;
    credential.SetPassword(&HSTRING::from(&passphrase))?;
    settings.SetSsid(&HSTRING::from(&ssid))?;
    settings.SetPassphrase(&credential)?;

    let listener = WiFiDirectConnectionListener::new()?;
    let (requests, peer) = mpsc::sync_channel::<WiFiDirectConnectionRequest>(1);
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
    let session = Session {
        publisher,
        listener,
        token,
        wlan,
        primary_wifi,
        primary_internet,
        deadline: Instant::now() + Duration::from_secs(150),
    };
    session.publisher.Start()?;
    let startup = Instant::now() + Duration::from_secs(10);
    while session.publisher.Status()? != WiFiDirectAdvertisementPublisherStatus::Started {
        session.check()?;
        if Instant::now() >= startup {
            return Err("Windows did not start a Wi-Fi Direct group".into());
        }
        thread::sleep(TICK);
    }
    session.check()?;
    println!("Nearby Link lab · 16 MiB each direction · expires in 150 seconds");
    println!("Enter on the Android probe only (do not join this in Wi-Fi settings):");
    println!("SSID: {ssid}\nPassphrase: {passphrase}");

    let request = loop {
        session.check()?;
        match peer.recv_timeout(TICK) {
            Ok(request) => break request,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(error) => return Err(error.into()),
        }
    };
    // One client only. Stop receiving new requests, including during TCP setup.
    drop(peer);
    let device = wait_for(
        WiFiDirectDevice::FromIdAsync(&request.DeviceInformation()?.Id()?)?,
        session.deadline,
        || session.check(),
    )?;
    let endpoints = device.GetConnectionEndpointPairs()?;
    let endpoint = (0..endpoints.Size()?).find_map(|index| {
        let pair = endpoints.GetAt(index).ok()?;
        let local: Ipv4Addr = pair
            .LocalHostName()
            .ok()?
            .CanonicalName()
            .ok()?
            .to_string()
            .parse()
            .ok()?;
        let remote: Ipv4Addr = pair
            .RemoteHostName()
            .ok()?
            .CanonicalName()
            .ok()?
            .to_string()
            .parse()
            .ok()?;
        (local.is_private() && remote.is_private() && local != remote).then_some((local, remote))
    });
    let (local, remote) = endpoint.ok_or("Windows did not return a private IPv4 peer endpoint")?;
    let tcp = TcpListener::bind((local, wire::PORT))?;
    tcp.set_nonblocking(true)?;
    let stream = loop {
        session.check()?;
        match tcp.accept() {
            Ok((stream, address)) if address.ip() == std::net::IpAddr::V4(remote) => break stream,
            Ok(_) => continue,
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => thread::sleep(TICK),
            Err(error) => return Err(error.into()),
        }
    };
    drop(tcp);
    stream.set_nonblocking(false)?;
    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    let start = Instant::now();
    wire::host(&mut GuardedSocket {
        stream,
        session: &session,
        next_check: start,
    })?;
    session.check()?;
    println!(
        "Verified 16 MiB each direction over {local} ↔ {remote} in {:.2}s",
        start.elapsed().as_secs_f64()
    );
    let _ = device.Close();
    Ok(())
}

pub fn run() -> Result<()> {
    let command = std::env::args().nth(1);
    let command = match command.as_deref() {
        Some(command @ ("status" | "host")) => command,
        _ => return Err("Usage: dropflow-nearby-windows-lab status|host".into()),
    };
    unsafe { RoInitialize(RO_INIT_MULTITHREADED)? };
    let _runtime = Runtime;
    let wlan = Wlan::open()
        .map_err(|err| format!("Nearby Link unavailable: Windows WLAN is unavailable: {err}"))?;
    check_radios(&wlan).map_err(|err| format!("Nearby Link unavailable: {err}"))?;
    if command == "status" {
        println!(
            "Wi-Fi and BLE are enabled; Wi-Fi Direct driver and concurrency remain unverified."
        );
        return Ok(());
    }
    host(&wlan)
}
