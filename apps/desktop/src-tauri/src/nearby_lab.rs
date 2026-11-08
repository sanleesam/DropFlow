#[tauri::command]
pub fn start_nearby_link_lab(app: tauri::AppHandle) -> Result<(), String> {
    #[cfg(windows)]
    if option_env!("DROPFLOW_NEARBY_LAB") == Some("1") {
        return windows::start(&app);
    }
    let _ = app;
    Err("The Nearby Link lab is only available in the Windows Development preview.".into())
}

#[cfg(windows)]
mod windows {
    use std::os::windows::process::CommandExt;
    use std::process::{Child, Command};
    use std::sync::Mutex;
    use tauri::{path::BaseDirectory, AppHandle, Manager};

    const CREATE_NEW_CONSOLE: u32 = 0x0000_0010;
    const HOST_PATH: &str = "nearby-lab/dropflow-nearby-windows-lab.exe";
    static HOST: Mutex<Option<Child>> = Mutex::new(None);

    pub fn start(app: &AppHandle) -> Result<(), String> {
        let mut host = HOST.lock().map_err(|_| "Nearby Link state unavailable")?;
        if let Some(child) = host.as_mut() {
            if child.try_wait().map_err(|err| err.to_string())?.is_none() {
                return Err("A Nearby Link lab window is already open.".into());
            }
        }
        let executable = app
            .path()
            .resolve(HOST_PATH, BaseDirectory::Resource)
            .map_err(|err| err.to_string())?;
        // Fixed, packaged executable and arguments. Never run a PATH lookup or a shell.
        let child = Command::new(executable)
            .arg("host")
            .env("DROPFLOW_NEARBY_CONSOLE", "1")
            .creation_flags(CREATE_NEW_CONSOLE)
            .spawn()
            .map_err(|err| format!("Could not open the Nearby Link lab: {err}"))?;
        *host = Some(child);
        Ok(())
    }
}
