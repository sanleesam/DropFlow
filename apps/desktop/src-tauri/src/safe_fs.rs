//! Atomic, fail-closed moves. Never fall back to a replacing rename.
use std::{io, path::Path};

pub fn rename_noreplace(from: &Path, to: &Path) -> io::Result<()> {
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    {
        use std::{ffi::CString, os::unix::ffi::OsStrExt};
        let from = CString::new(from.as_os_str().as_bytes())
            .map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
        let to = CString::new(to.as_os_str().as_bytes())
            .map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
        #[cfg(target_os = "macos")]
        let result = {
            extern "C" {
                fn renamex_np(
                    from: *const std::ffi::c_char,
                    to: *const std::ffi::c_char,
                    flags: u32,
                ) -> i32;
            }
            // Darwin stdio.h: RENAME_EXCL = 0x4. Both C strings live for the call.
            unsafe { renamex_np(from.as_ptr(), to.as_ptr(), 0x4) }
        };
        #[cfg(target_os = "linux")]
        let result = {
            extern "C" {
                fn renameat2(
                    old_dir: i32,
                    from: *const std::ffi::c_char,
                    new_dir: i32,
                    to: *const std::ffi::c_char,
                    flags: u32,
                ) -> i32;
            }
            // AT_FDCWD = -100, RENAME_NOREPLACE = 1; unsupported filesystems fail.
            unsafe { renameat2(-100, from.as_ptr(), -100, to.as_ptr(), 1) }
        };
        if result == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        #[link(name = "kernel32")]
        extern "system" {
            fn MoveFileExW(from: *const u16, to: *const u16, flags: u32) -> i32;
        }
        let from: Vec<u16> = from.as_os_str().encode_wide().chain(Some(0)).collect();
        let to: Vec<u16> = to.as_os_str().encode_wide().chain(Some(0)).collect();
        if from[..from.len() - 1].contains(&0) || to[..to.len() - 1].contains(&0) {
            return Err(io::Error::from(io::ErrorKind::InvalidInput));
        }
        // No MOVEFILE_REPLACE_EXISTING and no copy fallback. Arrays are NUL terminated.
        if unsafe { MoveFileExW(from.as_ptr(), to.as_ptr(), 0) } != 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux", windows)))]
    {
        let _ = (from, to);
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "Exclusive rename is unavailable on this platform",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        sync::{Arc, Barrier},
        thread,
    };
    #[test]
    fn concurrent_moves_never_replace_an_existing_file() {
        let root =
            std::env::temp_dir().join(format!("dropflow-exclusive-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        let a = root.join("a");
        let b = root.join("b");
        let target = root.join("target");
        fs::write(&a, b"a").unwrap();
        fs::write(&b, b"b").unwrap();
        let gate = Arc::new(Barrier::new(2));
        let handles: Vec<_> = [a, b]
            .into_iter()
            .map(|part| {
                let gate = gate.clone();
                let target = target.clone();
                thread::spawn(move || {
                    gate.wait();
                    rename_noreplace(&part, &target)
                })
            })
            .collect();
        let outcomes: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        assert_eq!(outcomes.iter().filter(|r| r.is_ok()).count(), 1);
        assert_eq!(
            outcomes
                .iter()
                .filter(|r| r
                    .as_ref()
                    .is_err_and(|e| e.kind() == io::ErrorKind::AlreadyExists))
                .count(),
            1
        );
        assert_eq!(fs::read_dir(&root).unwrap().count(), 2);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn directory_moves_are_exclusive_too() {
        let root =
            std::env::temp_dir().join(format!("dropflow-exclusive-dir-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(root.join("a")).unwrap();
        fs::create_dir(root.join("b")).unwrap();
        assert!(rename_noreplace(&root.join("a"), &root.join("b")).is_err());
        assert!(root.join("a").is_dir());
        rename_noreplace(&root.join("a"), &root.join("c")).unwrap();
        fs::remove_dir_all(root).unwrap();
    }
}
