use std::fs;
use std::path::{Component, Path, PathBuf};

/// Default subdirectory inside user's Downloads directory for received files.
const DROPFLOW_DOWNLOAD_SUBDIR: &str = "DropFlow";

/// Sanitizes an incoming filename string to prevent invalid characters and path traversal.
pub fn sanitize_filename(filename: &str) -> String {
    let trimmed = filename.trim();
    if trimmed.is_empty() {
        return "unnamed_file".to_string();
    }

    // Normalize Windows backslashes to forward slashes before getting file basename
    let normalized = trimmed.replace('\\', "/");
    let path = Path::new(&normalized);
    let basename = path
        .file_name()
        .and_then(|os_str| os_str.to_str())
        .unwrap_or("unnamed_file");

    // Filter illegal characters (null bytes, control chars, Windows/Unix reserved chars)
    let sanitized: String = basename
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\0' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();

    let clean = sanitized.trim();
    if clean.is_empty() || clean == "." || clean == ".." {
        "unnamed_file".to_string()
    } else {
        clean.to_string()
    }
}

/// Sanitizes an incoming relative path to ensure no path traversal (e.g. `../`) occurs.
pub fn sanitize_relative_path(raw_path: &str) -> Result<PathBuf, String> {
    let trimmed = raw_path.trim();
    if trimmed.is_empty() {
        return Err("Relative path cannot be empty".to_string());
    }

    let normalized = trimmed.replace('\\', "/");
    let path = Path::new(&normalized);
    if path.is_absolute()
        || normalized.starts_with('/')
        || (normalized.len() >= 2 && normalized.chars().nth(1) == Some(':'))
    {
        return Err(format!("Absolute paths are not allowed: {trimmed}"));
    }

    let mut safe_path = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Normal(os_str) => {
                let name = os_str.to_string_lossy();
                let clean_name = sanitize_filename(&name);
                safe_path.push(clean_name);
            }
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(format!("Illegal path component in relative path: {trimmed}"));
            }
            Component::CurDir => {}
        }
    }

    if safe_path.as_os_str().is_empty() {
        return Err("Sanitized relative path is empty".to_string());
    }

    Ok(safe_path)
}

/// Returns the default controlled directory for saving received files (~/Downloads/DropFlow).
pub fn get_default_receive_dir() -> Result<PathBuf, String> {
    let downloads_dir = match std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")) {
        Some(home) => PathBuf::from(home).join("Downloads"),
        None => std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
    };

    let receive_dir = downloads_dir.join(DROPFLOW_DOWNLOAD_SUBDIR);
    if !receive_dir.exists() {
        fs::create_dir_all(&receive_dir)
            .map_err(|e| format!("Failed to create DropFlow receive directory: {e}"))?;
    }

    Ok(receive_dir)
}

/// Verifies that a target file path remains strictly inside the base receive directory.
pub fn verify_safe_target_path(base_dir: &Path, rel_path: &Path) -> Result<PathBuf, String> {
    let target = base_dir.join(rel_path);

    // Ensure target starts with base_dir prefix
    if !target.starts_with(base_dir) {
        return Err(format!(
            "Security alert: target path {:?} escapes base directory {:?}",
            target, base_dir
        ));
    }

    Ok(target)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sanitize_filename_basic() {
        assert_eq!(sanitize_filename("test.png"), "test.png");
        assert_eq!(sanitize_filename("../../etc/passwd"), "passwd");
        assert_eq!(sanitize_filename("C:\\Windows\\system32\\cmd.exe"), "cmd.exe");
        assert_eq!(sanitize_filename("  "), "unnamed_file");
        assert_eq!(sanitize_filename("bad:name?.txt"), "bad_name_.txt");
    }

    #[test]
    fn test_sanitize_relative_path_traversal() {
        assert!(sanitize_relative_path("/etc/passwd").is_err());
        assert!(sanitize_relative_path("C:\\Windows\\system32").is_err());
        assert!(sanitize_relative_path("../secret.txt").is_err());
        assert!(sanitize_relative_path("folder/../../secret.txt").is_err());

        let clean = sanitize_relative_path("photos/2026/vacation.jpg").unwrap();
        assert_eq!(clean, PathBuf::from("photos").join("2026").join("vacation.jpg"));
    }

    #[test]
    fn test_verify_safe_target_path() {
        let base = PathBuf::from("/Users/test/Downloads/DropFlow");
        let safe_rel = PathBuf::from("docs/report.pdf");
        let target = verify_safe_target_path(&base, &safe_rel).unwrap();
        assert_eq!(target, base.join("docs/report.pdf"));
    }
}
