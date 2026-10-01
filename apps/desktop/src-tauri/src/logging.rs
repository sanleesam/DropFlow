//! Structured logging for DropFlow desktop.
//!
//! Writes every `log` facade record to stderr and to a rotating file under the
//! application data directory so field issues (discovery, firewall, transfers)
//! can be diagnosed from real hardware without a console attached.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use chrono::Utc;
use log::{Level, LevelFilter, Metadata, Record};

const LOG_FILE_NAME: &str = "dropflow.log";
const LOG_ROTATE_BYTES: u64 = 5 * 1024 * 1024; // 5 MB per file, one rotated backup

struct DropFlowLogger {
    file: Mutex<Option<File>>,
    file_path: PathBuf,
}

impl log::Log for DropFlowLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= if cfg!(debug_assertions) {
            Level::Debug
        } else {
            Level::Info
        }
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }

        let timestamp = Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ");
        let line = format!(
            "{timestamp} [{:<5}] [{}] {}",
            record.level(),
            record.target(),
            record.args()
        );

        // stderr for interactive runs; never panic the app on logging failures.
        eprintln!("{line}");

        if let Ok(mut guard) = self.file.lock() {
            if guard.is_none() {
                *guard = open_log_file(&self.file_path);
            }
            if let Some(file) = guard.as_mut() {
                if writeln!(file, "{line}").is_err() {
                    *guard = None; // Re-open on next record (e.g. file moved/deleted)
                }
            }
        }
    }

    fn flush(&self) {}
}

fn open_log_file(path: &Path) -> Option<File> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).ok()?;
    }
    rotate_if_needed(path);
    OpenOptions::new().create(true).append(true).open(path).ok()
}

fn rotate_if_needed(path: &Path) {
    let Ok(metadata) = std::fs::metadata(path) else {
        return;
    };
    if metadata.len() < LOG_ROTATE_BYTES {
        return;
    }
    let rotated = path.with_extension("log.old");
    let _ = std::fs::remove_file(&rotated);
    let _ = std::fs::rename(path, rotated);
}

/// Initializes the global logger. Call once during app setup; later calls are
/// ignored. Returns the log file path when initialization succeeded.
pub fn init(log_dir: &Path) -> Option<PathBuf> {
    let file_path = log_dir.join(LOG_FILE_NAME);

    let logger = DropFlowLogger {
        file: Mutex::new(None),
        file_path: file_path.clone(),
    };

    let level = if cfg!(debug_assertions) {
        LevelFilter::Debug
    } else {
        LevelFilter::Info
    };

    // Env-based override wins (e.g. RUST_LOG=trace for field debugging).
    let filter = std::env::var("RUST_LOG")
        .ok()
        .and_then(|spec| spec.parse::<LevelFilter>().ok())
        .unwrap_or(level);

    let set = log::set_boxed_logger(Box::new(logger)).is_ok();
    if set {
        log::set_max_level(filter);
        log::info!(
            "Logging initialized (level {filter}, file {})",
            file_path.display()
        );
        Some(file_path)
    } else {
        None
    }
}
