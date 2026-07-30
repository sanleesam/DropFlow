use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

#[cfg(target_os = "macos")]
#[allow(non_upper_case_globals)]
mod platform_macos {
    use std::ffi::{c_char, c_void, CString};

    pub type IOPMAssertionID = u32;
    pub type IOReturn = i32;
    pub type CFStringRef = *const c_void;

    #[link(name = "CoreFoundation", kind = "framework")]
    extern "C" {
        fn CFStringCreateWithCString(
            alloc: CFStringRef,
            c_str: *const c_char,
            encoding: u32,
        ) -> CFStringRef;

        fn CFRelease(cf: CFStringRef);
    }

    #[link(name = "IOKit", kind = "framework")]
    extern "C" {
        fn IOPMAssertionCreateWithName(
            assertion_type: CFStringRef,
            assertion_level: u32,
            assertion_name: CFStringRef,
            assertion_id: *mut IOPMAssertionID,
        ) -> IOReturn;

        fn IOPMAssertionRelease(assertion_id: IOPMAssertionID) -> IOReturn;
    }

    const kIOPMAssertionLevelOn: u32 = 255;
    const kCFStringEncodingUTF8: u32 = 0x08000100;

    pub fn create_sleep_assertion() -> Option<IOPMAssertionID> {
        unsafe {
            let type_str = CString::new("PreventUserIdleSystemSleep").ok()?;
            let name_str = CString::new("DropFlow File Transfer Active").ok()?;

            let cf_type = CFStringCreateWithCString(
                std::ptr::null(),
                type_str.as_ptr(),
                kCFStringEncodingUTF8,
            );
            let cf_name = CFStringCreateWithCString(
                std::ptr::null(),
                name_str.as_ptr(),
                kCFStringEncodingUTF8,
            );

            if cf_type.is_null() || cf_name.is_null() {
                if !cf_type.is_null() {
                    CFRelease(cf_type);
                }
                if !cf_name.is_null() {
                    CFRelease(cf_name);
                }
                return None;
            }

            let mut assertion_id: IOPMAssertionID = 0;
            let ret = IOPMAssertionCreateWithName(
                cf_type,
                kIOPMAssertionLevelOn,
                cf_name,
                &mut assertion_id,
            );

            CFRelease(cf_type);
            CFRelease(cf_name);

            if ret == 0 {
                println!(
                    "[PowerManager] Successfully acquired macOS IOPMAssertion ({assertion_id})"
                );
                Some(assertion_id)
            } else {
                eprintln!(
                    "[PowerManager] Failed to create macOS IOPMAssertion, return code: {ret}"
                );
                None
            }
        }
    }

    pub fn release_sleep_assertion(assertion_id: IOPMAssertionID) {
        unsafe {
            let ret = IOPMAssertionRelease(assertion_id);
            if ret == 0 {
                println!(
                    "[PowerManager] Successfully released macOS IOPMAssertion ({assertion_id})"
                );
            } else {
                eprintln!("[PowerManager] Failed to release macOS IOPMAssertion ({assertion_id}), return code: {ret}");
            }
        }
    }
}

#[cfg(target_os = "windows")]
mod platform_windows {
    pub type DWORD = u32;
    pub const ES_CONTINUOUS: DWORD = 0x80000000;
    pub const ES_SYSTEM_REQUIRED: DWORD = 0x00000001;

    #[link(name = "kernel32")]
    extern "system" {
        fn SetThreadExecutionState(esFlags: DWORD) -> DWORD;
    }

    pub fn create_sleep_assertion() -> bool {
        unsafe {
            let ret = SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
            if ret != 0 {
                println!("[PowerManager] Successfully acquired Windows SetThreadExecutionState assertion");
                true
            } else {
                eprintln!("[PowerManager] Failed to set Windows SetThreadExecutionState assertion");
                false
            }
        }
    }

    pub fn release_sleep_assertion() {
        unsafe {
            SetThreadExecutionState(ES_CONTINUOUS);
            println!(
                "[PowerManager] Successfully released Windows SetThreadExecutionState assertion"
            );
        }
    }
}

pub struct SleepState {
    pub sleep_manager: Arc<SleepManager>,
}

pub struct SleepManager {
    active_transfers: AtomicUsize,
    #[cfg(target_os = "macos")]
    macos_assertion_id: Mutex<Option<u32>>,
}

impl SleepManager {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            active_transfers: AtomicUsize::new(0),
            #[cfg(target_os = "macos")]
            macos_assertion_id: Mutex::new(None),
        })
    }

    pub fn acquire(self: &Arc<Self>) -> SleepPreventionGuard {
        let prev_count = self.active_transfers.fetch_add(1, Ordering::SeqCst);
        if prev_count == 0 {
            self.platform_acquire();
        }
        SleepPreventionGuard {
            manager: Arc::clone(self),
        }
    }

    pub fn release(&self) {
        let prev_count = self
            .active_transfers
            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |val| {
                Some(val.saturating_sub(1))
            })
            .unwrap_or(0);

        if prev_count == 1 {
            self.platform_release();
        }
    }

    pub fn active_count(&self) -> usize {
        self.active_transfers.load(Ordering::SeqCst)
    }

    fn platform_acquire(&self) {
        println!("[PowerManager] Sleep prevention acquired (active_transfers: 1)");
        #[cfg(target_os = "macos")]
        {
            if let Some(id) = platform_macos::create_sleep_assertion() {
                if let Ok(mut guard) = self.macos_assertion_id.lock() {
                    *guard = Some(id);
                }
            }
        }
        #[cfg(target_os = "windows")]
        {
            platform_windows::create_sleep_assertion();
        }
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        {
            println!("[PowerManager] Linux platform: System sleep prevention requested (no-op fallback).");
        }
    }

    fn platform_release(&self) {
        println!("[PowerManager] Sleep prevention released (active_transfers: 0)");
        #[cfg(target_os = "macos")]
        {
            if let Ok(mut guard) = self.macos_assertion_id.lock() {
                if let Some(id) = guard.take() {
                    platform_macos::release_sleep_assertion(id);
                }
            }
        }
        #[cfg(target_os = "windows")]
        {
            platform_windows::release_sleep_assertion();
        }
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        {
            println!(
                "[PowerManager] Linux platform: System sleep prevention released (no-op fallback)."
            );
        }
    }
}

pub struct SleepPreventionGuard {
    manager: Arc<SleepManager>,
}

impl Drop for SleepPreventionGuard {
    fn drop(&mut self) {
        self.manager.release();
    }
}

// ─── Tests ───────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sleep_manager_acquire_and_release() {
        let manager = SleepManager::new();
        assert_eq!(manager.active_count(), 0);

        {
            let _guard = manager.acquire();
            assert_eq!(manager.active_count(), 1);
        }

        assert_eq!(manager.active_count(), 0);
    }

    #[test]
    fn test_sleep_manager_concurrent_transfers() {
        let manager = SleepManager::new();
        assert_eq!(manager.active_count(), 0);

        let guard1 = manager.acquire();
        assert_eq!(manager.active_count(), 1);

        let guard2 = manager.acquire();
        assert_eq!(manager.active_count(), 2);

        let guard3 = manager.acquire();
        assert_eq!(manager.active_count(), 3);

        drop(guard1);
        assert_eq!(manager.active_count(), 2);

        drop(guard2);
        assert_eq!(manager.active_count(), 1);

        drop(guard3);
        assert_eq!(manager.active_count(), 0);
    }

    #[test]
    fn test_sleep_manager_saturating_sub_safety() {
        let manager = SleepManager::new();
        assert_eq!(manager.active_count(), 0);

        manager.release();
        assert_eq!(manager.active_count(), 0);
    }
}
