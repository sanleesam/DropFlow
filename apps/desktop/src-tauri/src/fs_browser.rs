use serde::Serialize;
use std::path::{Path, PathBuf};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiskUsage {
    pub total_bytes: u64,
    pub free_bytes: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FolderRoots {
    pub home: String,
    pub desktop: String,
    pub documents: String,
    pub downloads: String,
    pub local_ip: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ListedEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub size_bytes: u64,
    pub modified_ms: u64,
}

fn home_dir() -> Result<PathBuf, String> {
    let raw = std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .ok_or_else(|| "No home folder".to_string())?;
    Ok(PathBuf::from(raw))
}

fn inside_home(path: &Path) -> Result<PathBuf, String> {
    let home = home_dir()?.canonicalize().map_err(|err| err.to_string())?;
    if !path.exists() {
        return Err("That folder doesn't exist".into());
    }
    let candidate = path.canonicalize().map_err(|err| err.to_string())?;
    if !candidate.starts_with(&home) {
        return Err("That folder is outside your user folder".into());
    }
    Ok(candidate)
}

fn plain_name(name: &str) -> Result<&str, String> {
    let name = name.trim();
    if name.is_empty() || crate::transfer_manager::security::sanitize_filename(name) != name {
        return Err("Use a plain file name".into());
    }
    Ok(name)
}

/// Resolve the parent for boundary checking, but never follow the selected leaf.
/// This also permits removing a dangling symlink without touching its target.
fn mutation_path(path: &Path) -> Result<PathBuf, String> {
    if !path.is_absolute() || path.components().any(|c| matches!(c, std::path::Component::ParentDir)) {
        return Err("Use an absolute path without parent traversal".into());
    }
    let parent = path.parent().ok_or("Cannot change a filesystem root")?;
    let parent = inside_home(parent)?;
    let leaf = path.file_name().ok_or("Cannot change a filesystem root")?;
    let target = parent.join(leaf);
    std::fs::symlink_metadata(&target).map_err(|err| err.to_string())?;
    Ok(target)
}

fn local_ipv4() -> String {
    let socket = match std::net::UdpSocket::bind("0.0.0.0:0") {
        Ok(socket) => socket,
        Err(_) => return String::new(),
    };
    if socket.connect("8.8.8.8:80").is_err() {
        return String::new();
    }
    match socket.local_addr() {
        Ok(addr) => addr.ip().to_string(),
        Err(_) => String::new(),
    }
}

#[cfg(windows)]
fn disk_usage_of(path: &Path) -> Result<(u64, u64), String> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    extern "system" {
        fn GetDiskFreeSpaceExW(
            lp_directory_name: *const u16,
            lp_free_bytes_available: *mut u64,
            lp_total_bytes: *mut u64,
            lp_total_free_bytes: *mut u64,
        ) -> i32;
    }
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
    let mut available = 0u64;
    let mut total = 0u64;
    let mut free = 0u64;
    let ok = unsafe { GetDiskFreeSpaceExW(wide.as_ptr(), &mut available, &mut total, &mut free) };
    if ok == 0 {
        return Err("Couldn't read disk space".into());
    }
    Ok((total, free))
}

#[cfg(not(windows))]
fn disk_usage_of(_path: &Path) -> Result<(u64, u64), String> {
    Err("Disk space is only read on Windows in this build".into())
}

#[tauri::command]
pub fn get_disk_usage(path: String) -> Result<DiskUsage, String> {
    let root = if path.trim().is_empty() { home_dir()? } else { PathBuf::from(path) };
    let (total_bytes, free_bytes) = disk_usage_of(&root)?;
    Ok(DiskUsage { total_bytes, free_bytes })
}

#[tauri::command]
pub fn get_user_folders() -> Result<FolderRoots, String> {
    let home = home_dir()?;
    let join = |name: &str| home.join(name).to_string_lossy().to_string();
    Ok(FolderRoots {
        home: home.to_string_lossy().to_string(),
        desktop: join("Desktop"),
        documents: join("Documents"),
        downloads: join("Downloads"),
        local_ip: local_ipv4(),
    })
}

#[tauri::command]
pub fn list_directory(path: String) -> Result<Vec<ListedEntry>, String> {
    let dir = inside_home(Path::new(&path))?;
    let mut entries = Vec::new();
    for item in std::fs::read_dir(&dir).map_err(|err| err.to_string())? {
        let item = match item {
            Ok(item) => item,
            Err(_) => continue,
        };
        let name = item.file_name().to_string_lossy().to_string();
        if name.starts_with('.') {
            continue;
        }
        let meta = match item.metadata() {
            Ok(meta) => meta,
            Err(_) => continue,
        };
        let modified_ms = meta
            .modified()
            .ok()
            .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|duration| duration.as_millis() as u64)
            .unwrap_or(0);
        entries.push(ListedEntry {
            name,
            path: item.path().to_string_lossy().to_string(),
            is_dir: meta.is_dir(),
            size_bytes: if meta.is_file() { meta.len() } else { 0 },
            modified_ms,
        });
    }
    entries.sort_by(|a, b| b.is_dir.cmp(&a.is_dir).then(a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    Ok(entries)
}

#[tauri::command]
pub fn read_image_preview(path: String) -> Result<String, String> {
    let file = inside_home(Path::new(&path))?;
    let ext = file.extension().and_then(|ext| ext.to_str()).unwrap_or("").to_ascii_lowercase();
    let mime = match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        _ => return Err("Not an image".into()),
    };
    let meta = std::fs::metadata(&file).map_err(|err| err.to_string())?;
    if meta.len() > 6 * 1024 * 1024 {
        return Err("Image is too large to preview".into());
    }
    let bytes = std::fs::read(&file).map_err(|err| err.to_string())?;
    Ok(format!("data:{mime};base64,{}", encode_base64(&bytes)))
}

#[tauri::command]
pub fn rename_path(path: String, new_name: String) -> Result<String, String> {
    let name = plain_name(&new_name)?;
    let file = mutation_path(Path::new(&path))?;
    let dest = file.parent().ok_or_else(|| "No parent folder".to_string())?.join(name);
    crate::safe_fs::rename_noreplace(&file, &dest).map_err(|err| {
        if err.kind() == std::io::ErrorKind::AlreadyExists { "A file or folder with that name already exists".into() }
        else { err.to_string() }
    })?;
    Ok(dest.to_string_lossy().to_string())
}

#[tauri::command]
pub fn delete_path(path: String) -> Result<(), String> {
    let file = mutation_path(Path::new(&path))?;
    let meta = std::fs::symlink_metadata(&file).map_err(|err| err.to_string())?;
    #[cfg(windows)]
    if meta.file_type().is_symlink() && std::os::windows::fs::FileTypeExt::is_symlink_dir(&meta.file_type()) {
        return std::fs::remove_dir(&file).map_err(|err| err.to_string());
    }
    if meta.is_dir() {
        std::fs::remove_dir(&file).map_err(|_| "That folder isn't empty".to_string())
    } else {
        std::fs::remove_file(&file).map_err(|err| err.to_string())
    }
}

#[tauri::command]
pub fn create_folder(parent: String, name: String) -> Result<String, String> {
    let name = plain_name(&name)?;
    let dir = inside_home(Path::new(&parent))?;
    let dest = dir.join(name);
    std::fs::create_dir(&dest).map_err(|err| err.to_string())?;
    Ok(dest.to_string_lossy().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(unix)]
    #[test]
    fn deleting_and_renaming_links_never_changes_the_target() {
        let root=home_dir().unwrap().join(format!(".dropflow-link-test-{}",uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let target=root.join("original.txt");std::fs::write(&target,b"keep me").unwrap();
        let link=root.join("shortcut.txt");std::os::unix::fs::symlink(&target,&link).unwrap();
        let renamed=rename_path(link.to_string_lossy().into_owned(),"renamed-link.txt".into()).unwrap();
        assert!(std::fs::symlink_metadata(&renamed).unwrap().file_type().is_symlink());
        assert_eq!(std::fs::read(&target).unwrap(),b"keep me");
        delete_path(renamed).unwrap();assert!(target.exists());
        std::os::unix::fs::symlink(root.join("missing"),&link).unwrap();
        delete_path(link.to_string_lossy().into_owned()).unwrap();
        assert!(std::fs::symlink_metadata(link).is_err());
        assert_eq!(std::fs::read(target).unwrap(),b"keep me");
        assert!(mutation_path(&root.join("../escape")).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn browser_operations_preserve_existing_files_and_enforce_boundaries() {
        let root = home_dir().unwrap().join(format!(".dropflow-fs-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let root_s = root.to_string_lossy().into_owned();
        let dir = create_folder(root_s.clone(), "nested".into()).unwrap();
        let file = root.join("source.txt");
        std::fs::write(&file, b"source").unwrap();
        std::fs::write(root.join("existing.txt"), b"keep").unwrap();
        std::fs::write(root.join(".hidden"), b"hidden").unwrap();
        let list = list_directory(root_s.clone()).unwrap();
        assert!(list[0].is_dir);
        assert!(!list.iter().any(|entry| entry.name.starts_with('.')));
        assert!(rename_path(file.to_string_lossy().into_owned(), "existing.txt".into()).is_err());
        assert_eq!(std::fs::read(root.join("existing.txt")).unwrap(), b"keep");
        let renamed = rename_path(file.to_string_lossy().into_owned(), "renamed.txt".into()).unwrap();
        assert_eq!(std::fs::read(&renamed).unwrap(), b"source");
        assert!(create_folder(root_s, "../escape".into()).is_err());
        delete_path(renamed).unwrap();
        delete_path(dir).unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
}

fn encode_base64(data: &[u8]) -> String {
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    let mut index = 0;
    while index + 3 <= data.len() {
        let chunk = ((data[index] as u32) << 16) | ((data[index + 1] as u32) << 8) | data[index + 2] as u32;
        out.push(TABLE[((chunk >> 18) & 63) as usize] as char);
        out.push(TABLE[((chunk >> 12) & 63) as usize] as char);
        out.push(TABLE[((chunk >> 6) & 63) as usize] as char);
        out.push(TABLE[(chunk & 63) as usize] as char);
        index += 3;
    }
    let rest = data.len() - index;
    if rest == 1 {
        let chunk = (data[index] as u32) << 16;
        out.push(TABLE[((chunk >> 18) & 63) as usize] as char);
        out.push(TABLE[((chunk >> 12) & 63) as usize] as char);
        out.push('=');
        out.push('=');
    } else if rest == 2 {
        let chunk = ((data[index] as u32) << 16) | ((data[index + 1] as u32) << 8);
        out.push(TABLE[((chunk >> 18) & 63) as usize] as char);
        out.push(TABLE[((chunk >> 12) & 63) as usize] as char);
        out.push(TABLE[((chunk >> 6) & 63) as usize] as char);
        out.push('=');
    }
    out
}
