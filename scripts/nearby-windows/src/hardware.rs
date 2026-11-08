#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BluetoothStatus {
    Available,
    Unavailable,
}

/// Only Wi-Fi is required. BLE availability is advisory, because manual
/// pairing and the actual exchange both run over the same Wi-Fi Direct link.
pub fn preflight<E>(
    wifi: Result<(), E>,
    check_ble: impl FnOnce() -> Result<bool, E>,
) -> Result<BluetoothStatus, E> {
    wifi?;
    Ok(match check_ble() {
        Ok(true) => BluetoothStatus::Available,
        Ok(false) | Err(_) => BluetoothStatus::Unavailable,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wifi_only_is_allowed_when_ble_is_missing_off_or_unreadable() {
        for ble in [Ok(false), Err("no adapter"), Err("radio access denied")] {
            assert_eq!(preflight(Ok(()), || ble), Ok(BluetoothStatus::Unavailable));
        }
    }

    #[test]
    fn dual_radio_systems_keep_the_existing_path() {
        assert_eq!(
            preflight::<&str>(Ok(()), || Ok(true)),
            Ok(BluetoothStatus::Available),
        );
    }

    #[test]
    fn bluetooth_does_not_substitute_for_wifi() {
        assert_eq!(
            preflight(Err("no Wi-Fi"), || panic!(
                "skip BLE when Wi-Fi is unavailable"
            )),
            Err("no Wi-Fi"),
        );
    }
}
