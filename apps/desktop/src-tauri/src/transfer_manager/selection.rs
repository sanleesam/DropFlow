use std::path::{Path, PathBuf};

/// Expand selected folders without following symlinks (which can form cycles).
/// Paths on the wire always use '/' and retain the selected folder's name.
pub fn expand_selection(paths: &[String]) -> Result<Vec<(PathBuf, String)>, String> {
    fn visit(
        path: &Path,
        base: &Path,
        depth: usize,
        out: &mut Vec<(PathBuf, String)>,
    ) -> Result<(), String> {
        if depth > 128 {
            return Err("Selected folder is nested too deeply".into());
        }
        let meta = std::fs::symlink_metadata(path)
            .map_err(|e| format!("Cannot read {}: {e}", path.display()))?;
        if meta.file_type().is_symlink() {
            return Ok(());
        }
        if meta.is_dir() {
            let mut children = std::fs::read_dir(path)
                .map_err(|e| format!("Cannot read folder {}: {e}", path.display()))?
                .map(|entry| entry.map(|e| e.path()))
                .collect::<Result<Vec<_>, _>>()
                .map_err(|e| format!("Cannot list folder {}: {e}", path.display()))?;
            children.sort();
            for child in children {
                visit(&child, base, depth + 1, out)?;
            }
        } else if meta.is_file() {
            let relative = path.strip_prefix(base).map_err(|e| e.to_string())?;
            let name = relative
                .components()
                .map(|part| {
                    part.as_os_str()
                        .to_str()
                        .ok_or_else(|| "A selected file name is not valid UTF-8".to_string())
                })
                .collect::<Result<Vec<_>, _>>()?
                .join("/");
            out.push((path.to_path_buf(), name));
        }
        Ok(())
    }
    let mut files = Vec::new();
    for selected in paths {
        let path = Path::new(selected);
        let before = files.len();
        let base = path.parent().unwrap_or_else(|| Path::new(""));
        visit(path, base, 0, &mut files)?;
        if files.len() == before {
            return Err(format!("No regular files to send in {} (empty folders and symbolic links are not transferred)", path.display()));
        }
    }
    Ok(files)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn expands_nested_folders_and_preserves_names() {
        let root = std::env::temp_dir().join(format!("dropflow-selection-{}", std::process::id()));
        let folder = root.join("Folder with spaces");
        std::fs::create_dir_all(folder.join("nested")).unwrap();
        std::fs::write(folder.join("a.txt"), b"a").unwrap();
        std::fs::write(folder.join("nested/b.txt"), b"b").unwrap();
        let files = expand_selection(&[folder.to_string_lossy().into_owned()]).unwrap();
        assert_eq!(
            files
                .iter()
                .map(|(_, name)| name.as_str())
                .collect::<Vec<_>>(),
            vec![
                "Folder with spaces/a.txt",
                "Folder with spaces/nested/b.txt"
            ]
        );
        let single =
            expand_selection(&[folder.join("a.txt").to_string_lossy().into_owned()]).unwrap();
        assert_eq!(single[0].1, "a.txt");
        std::fs::create_dir_all(root.join("empty")).unwrap();
        assert!(expand_selection(&[root.join("empty").to_string_lossy().into_owned()]).is_err());
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&folder, folder.join("loop")).unwrap();
            assert_eq!(
                expand_selection(&[folder.to_string_lossy().into_owned()])
                    .unwrap()
                    .len(),
                2
            );
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}
