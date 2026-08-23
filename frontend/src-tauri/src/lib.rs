use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::Duration;

use tauri::{
    menu::{MenuBuilder, MenuItemBuilder},
    tray::TrayIconBuilder,
    Manager,
};

struct ServerState {
    process: Mutex<Option<Child>>,
    port: u16,
    shutdown_token: String,
}

#[derive(Default, Deserialize, Serialize)]
struct SeedManifest {
    version: String,
    files: BTreeMap<String, String>,
}

fn http_client() -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(2))
        .timeout(Duration::from_secs(4))
        .build()
        .expect("build local backend HTTP client")
}

fn backend_is_ready(port: u16) -> bool {
    let url = format!("http://127.0.0.1:{port}/api/health");
    let response = match http_client().get(url).send() {
        Ok(response) if response.status().is_success() => response,
        _ => return false,
    };
    response
        .json::<serde_json::Value>()
        .ok()
        .and_then(|body| {
            body.get("service")
                .and_then(|value| value.as_str())
                .map(str::to_owned)
        })
        .as_deref()
        == Some("ftsm-rag-java")
}

fn choose_port() -> (u16, bool) {
    if backend_is_ready(8000) {
        return (8000, true);
    }
    if std::net::TcpListener::bind("127.0.0.1:8000").is_ok() {
        return (8000, false);
    }
    (portpicker::pick_unused_port().unwrap_or(18000), false)
}

fn copy_missing_tree(source: &Path, destination: &Path) -> std::io::Result<()> {
    if !source.exists() {
        return Ok(());
    }
    if source.is_file() {
        if !destination.exists() {
            if let Some(parent) = destination.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::copy(source, destination)?;
        }
        return Ok(());
    }

    fs::create_dir_all(destination)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        copy_missing_tree(&entry.path(), &destination.join(entry.file_name()))?;
    }
    Ok(())
}

fn directory_has_entries(path: &Path) -> bool {
    fs::read_dir(path)
        .ok()
        .and_then(|mut entries| entries.next())
        .is_some()
}

fn file_sha256(path: &Path) -> std::io::Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

fn read_seed_manifest(path: &Path) -> Result<SeedManifest, String> {
    let content = fs::read_to_string(path)
        .map_err(|error| format!("Failed to read seed manifest {}: {error}", path.display()))?;
    serde_json::from_str(&content)
        .map_err(|error| format!("Failed to parse seed manifest {}: {error}", path.display()))
}

fn write_seed_manifest(path: &Path, manifest: &SeedManifest) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| format!("Seed state path has no parent: {}", path.display()))?;
    fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    let temp = path.with_extension("json.tmp");
    let content = serde_json::to_vec_pretty(manifest).map_err(|error| error.to_string())?;
    fs::write(&temp, content).map_err(|error| error.to_string())?;
    if path.exists() {
        fs::remove_file(path).map_err(|error| error.to_string())?;
    }
    fs::rename(&temp, path).map_err(|error| error.to_string())
}

fn sync_seed_data(
    backend_dir: &Path,
    app_data_dir: &Path,
    data_dir: &Path,
) -> Result<bool, String> {
    let seed_dir = backend_dir.join("seed-data");
    let packaged_manifest = read_seed_manifest(&backend_dir.join("seed-manifest.json"))?;
    let state_path = app_data_dir.join("seed-state.json");
    let previous_manifest = if state_path.is_file() {
        read_seed_manifest(&state_path).unwrap_or_default()
    } else {
        SeedManifest::default()
    };

    let mut changed = false;
    for (relative, packaged_hash) in &packaged_manifest.files {
        let source = seed_dir.join(relative);
        let destination = data_dir.join(relative);
        if !source.is_file() {
            continue;
        }

        let should_copy = if !destination.exists() {
            true
        } else if let Some(previous_hash) = previous_manifest.files.get(relative) {
            file_sha256(&destination)
                .map(|current_hash| {
                    current_hash == *previous_hash && current_hash != *packaged_hash
                })
                .unwrap_or(false)
        } else {
            false
        };

        if should_copy {
            if let Some(parent) = destination.parent() {
                fs::create_dir_all(parent).map_err(|error| error.to_string())?;
            }
            fs::copy(&source, &destination).map_err(|error| {
                format!(
                    "Failed to update bundled knowledge file {}: {error}",
                    destination.display()
                )
            })?;
            changed = true;
        }
    }

    write_seed_manifest(&state_path, &packaged_manifest)?;
    Ok(changed)
}

fn java_executable(backend_dir: &Path) -> PathBuf {
    let javaw = backend_dir.join("runtime").join("bin").join("javaw.exe");
    if javaw.is_file() {
        return javaw;
    }
    backend_dir.join("runtime").join("bin").join("java.exe")
}

fn start_backend(
    backend_dir: &Path,
    app_data_dir: &Path,
    port: u16,
    shutdown_token: &str,
) -> Result<Child, String> {
    let java = java_executable(backend_dir);
    let jar = backend_dir.join("rag-java.jar");
    let qdrant = backend_dir.join("qdrant").join("qdrant.exe");
    for required in [&java, &jar, &qdrant] {
        if !required.is_file() {
            return Err(format!(
                "Desktop backend resource is missing: {}",
                required.display()
            ));
        }
    }

    let data_dir = app_data_dir.join("data").join("ukm_ftsm");
    let qdrant_storage = app_data_dir.join("qdrant").join("storage");
    let lexical_index = app_data_dir.join("lucene");
    let has_existing_data = directory_has_entries(&data_dir);
    let has_existing_qdrant = directory_has_entries(&qdrant_storage);
    let has_existing_lexical = directory_has_entries(&lexical_index);
    let existing_install = has_existing_data || has_existing_qdrant || has_existing_lexical;
    fs::create_dir_all(&data_dir).map_err(|error| error.to_string())?;
    fs::create_dir_all(&qdrant_storage).map_err(|error| error.to_string())?;
    fs::create_dir_all(&lexical_index).map_err(|error| error.to_string())?;

    let seed_refresh_required = if existing_install {
        sync_seed_data(backend_dir, app_data_dir, &data_dir)?
            || (has_existing_data && (!has_existing_qdrant || !has_existing_lexical))
    } else {
        copy_missing_tree(&backend_dir.join("seed-data"), &data_dir)
            .map_err(|error| format!("Failed to initialize knowledge files: {error}"))?;
        copy_missing_tree(&backend_dir.join("seed-qdrant-storage"), &qdrant_storage)
            .map_err(|error| format!("Failed to initialize vector storage: {error}"))?;
        copy_missing_tree(&backend_dir.join("seed-lucene-storage"), &lexical_index)
            .map_err(|error| format!("Failed to initialize lexical storage: {error}"))?;
        let packaged_manifest = read_seed_manifest(&backend_dir.join("seed-manifest.json"))?;
        write_seed_manifest(&app_data_dir.join("seed-state.json"), &packaged_manifest)?;
        false
    };
    let seed_refresh_marker = app_data_dir.join("seed-refresh-required");
    if seed_refresh_required {
        fs::write(&seed_refresh_marker, b"pending")
            .map_err(|error| format!("Failed to mark knowledge refresh: {error}"))?;
    }
    copy_missing_tree(
        &backend_dir.join(".env.example"),
        &app_data_dir.join(".env.example"),
    )
    .map_err(|error| format!("Failed to initialize settings template: {error}"))?;

    let log_path = app_data_dir.join("backend.log");
    let stdout = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .map_err(|error| format!("Failed to open backend log: {error}"))?;
    let stderr = stdout
        .try_clone()
        .map_err(|error| format!("Failed to clone backend log handle: {error}"))?;

    let mut command = Command::new(java);
    command
        .current_dir(app_data_dir)
        .arg("-jar")
        .arg(jar)
        .arg(format!("--server.port={port}"))
        .arg("--server.address=127.0.0.1")
        .arg(format!("--app.qdrant.data-path={}", data_dir.display()))
        .arg(format!(
            "--app.qdrant.local-executable={}",
            qdrant.display()
        ))
        .arg(format!(
            "--app.qdrant.local-storage-path={}",
            qdrant_storage.display()
        ))
        .arg(format!(
            "--app.qdrant.lexical-index-path={}",
            lexical_index.display()
        ))
        .env("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
        .env("FTSM_DESKTOP_SHUTDOWN_TOKEN", shutdown_token)
        .env("FTSM_SEED_REFRESH_MARKER", &seed_refresh_marker)
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(stderr));

    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }

    command
        .spawn()
        .map_err(|error| format!("Failed to start Java backend: {error}"))
}

fn stop_backend(child: &mut Child, port: u16, shutdown_token: &str) {
    let url = format!("http://127.0.0.1:{port}/api/desktop/shutdown");
    let _ = http_client()
        .post(url)
        .header("X-FTSM-Shutdown-Token", shutdown_token)
        .send();

    let deadline = std::time::Instant::now() + Duration::from_secs(8);
    while std::time::Instant::now() < deadline {
        match child.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) => std::thread::sleep(Duration::from_millis(100)),
            Err(_) => break,
        }
    }
    let _ = child.kill();
    let _ = child.wait();
}

fn wait_for_backend(port: u16, timeout_seconds: u64) -> bool {
    let deadline = std::time::Instant::now() + Duration::from_secs(timeout_seconds);
    while std::time::Instant::now() < deadline {
        if backend_is_ready(port) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(300));
    }
    false
}

#[tauri::command]
fn get_server_url(state: tauri::State<ServerState>) -> String {
    format!("http://127.0.0.1:{}", state.port)
}

#[tauri::command]
fn quit_application(app: tauri::AppHandle) {
    app.exit(0);
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, _shortcut, event| {
                    if event.state == tauri_plugin_global_shortcut::ShortcutState::Pressed {
                        if let Some(window) = app.get_webview_window("main") {
                            if window.is_visible().unwrap_or(false) {
                                let _ = window.hide();
                            } else {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    }
                })
                .build(),
        )
        .setup(|app| {
            let resource_dir = app.path().resource_dir()?;
            let app_data_dir = app.path().app_local_data_dir()?;
            fs::create_dir_all(&app_data_dir)?;

            let (port, already_running) = choose_port();
            let shutdown_token = uuid::Uuid::new_v4().to_string();
            let process = if already_running {
                None
            } else {
                let backend_dir = resource_dir.join("java-backend");
                let mut child = start_backend(&backend_dir, &app_data_dir, port, &shutdown_token)
                    .map_err(std::io::Error::other)?;
                if !wait_for_backend(port, 90) {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(std::io::Error::other(format!(
                        "Java backend did not become ready. See {}",
                        app_data_dir.join("backend.log").display()
                    ))
                    .into());
                }
                Some(child)
            };
            app.manage(ServerState {
                process: Mutex::new(process),
                port,
                shutdown_token,
            });

            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }

            let show = MenuItemBuilder::with_id("show", "Show").build(app)?;
            let quit = MenuItemBuilder::with_id("quit", "Quit").build(app)?;
            let tray_menu = MenuBuilder::new(app).items(&[&show, &quit]).build()?;
            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("FTSM-RAG Assistant")
                .menu(&tray_menu)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app)?;

            use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut};
            let shortcut = Shortcut::new(Some(Modifiers::ALT), Code::Space);
            if let Err(error) = app.global_shortcut().register(shortcut) {
                log::warn!("Could not register Alt+Space shortcut: {error}");
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![get_server_url, quit_application])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running FTSM-RAG desktop application");
}

impl Drop for ServerState {
    fn drop(&mut self) {
        if let Ok(mut process) = self.process.lock() {
            if let Some(child) = process.as_mut() {
                stop_backend(child, self.port, &self.shutdown_token);
            }
        }
    }
}
