//! Window presentation only. No IPC commands or application settings are added.
use std::sync::atomic::{AtomicU8, Ordering};
use tauri::{Manager, WebviewWindow};

// Unknown initially, then solid / vibrancy / mica. All access is on the UI thread.
pub struct MaterialState(AtomicU8);

impl Default for MaterialState {
    fn default() -> Self {
        Self(AtomicU8::new(u8::MAX))
    }
}

#[cfg(target_os = "macos")]
fn desired_material() -> u8 {
    // WebKit versions without the CSS media query still get a native solid fallback.
    if objc2_app_kit::NSWorkspace::sharedWorkspace().accessibilityDisplayShouldReduceTransparency()
    {
        0
    } else {
        1
    }
}

#[cfg(target_os = "windows")]
fn desired_material() -> u8 {
    2
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn desired_material() -> u8 {
    0
}

fn apply(window: &WebviewWindow, desired: u8) -> Result<(), window_vibrancy::Error> {
    #[cfg(target_os = "macos")]
    {
        if desired == 0 {
            window_vibrancy::clear_vibrancy(window)?;
        } else {
            window_vibrancy::apply_vibrancy(
                window,
                window_vibrancy::NSVisualEffectMaterial::UnderWindowBackground,
                Some(window_vibrancy::NSVisualEffectState::FollowsWindowActiveState),
                None,
            )?;
        }
    }
    #[cfg(target_os = "windows")]
    {
        let _ = desired;
        // Acrylic's legacy implementation has known drag/resize regressions.
        // Mica is wallpaper-influenced, NOT a live behind-window blur.
        window_vibrancy::apply_mica(window, None)?;
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    let _ = (window, desired);
    Ok(())
}

pub fn refresh(window: &WebviewWindow) {
    let Some(state) = window.try_state::<MaterialState>() else {
        return;
    };
    let desired = desired_material();
    let mut current = state.0.load(Ordering::Relaxed);
    if current != desired {
        current = match apply(window, desired) {
            Ok(()) => desired,
            Err(error) => {
                log::warn!(
                    "[WindowMaterial] Native material unavailable; using solid surfaces: {error}"
                );
                0
            }
        };
        state.0.store(current, Ordering::Relaxed);
        log::info!("[WindowMaterial] mode={current} (0=solid, 1=vibrancy, 2=mica)");
    }

    // CSS remains opaque until native application succeeds. Republish after reload
    // and on activation, including changed macOS accessibility preferences.
    let material = match current {
        1 => "vibrancy",
        2 => "mica",
        _ => "solid",
    };
    if let Err(error) = window.eval(format!(
        "document.documentElement.dataset.nativeMaterial = '{material}';"
    )) {
        log::debug!("[WindowMaterial] Page not ready; solid CSS fallback retained: {error}");
    }
}
