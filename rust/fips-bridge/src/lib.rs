//! JNI bridge running an embedded FIPS (fips.network) node inside Citrine.
//!
//! The Kotlin side (`com.greenart7c3.citrine.service.FipsNative`) owns the TUN
//! device through a `VpnService`; this crate owns the FIPS endpoint and pumps
//! IPv6 packets between the TUN fd and the mesh. One node per process.

use std::os::fd::RawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use fips_endpoint::{Config, FipsEndpoint, NostrDiscoveryPolicy, TransportInstances, UdpConfig};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

struct EmbeddedNode {
    runtime: tokio::runtime::Runtime,
    endpoint: Arc<FipsEndpoint>,
    npub: String,
    address: String,
    tun_fd: Option<RawFd>,
    stop: Arc<AtomicBool>,
    pumps: Vec<JoinHandle<()>>,
}

static NODE: Mutex<Option<EmbeddedNode>> = Mutex::new(None);

/// Mesh-wide FIPS defaults, mirroring what `FipsEndpointBuilder` enables for
/// scoped app meshes but keeping the default (global) discovery scope so the
/// relay is part of the general FIPS network: open Nostr discovery over the
/// default advert relays plus a NAT-traversing UDP transport.
fn mesh_config() -> Config {
    let mut config = Config::new();
    config.node.discovery.nostr.enabled = true;
    config.node.discovery.nostr.advertise = true;
    config.node.discovery.nostr.policy = NostrDiscoveryPolicy::Open;
    config.node.discovery.nostr.share_local_candidates = true;
    config.node.discovery.local.enabled = true;
    config.transports.udp = TransportInstances::Single(UdpConfig {
        bind_addr: Some("0.0.0.0:0".to_string()),
        advertise_on_nostr: Some(true),
        public: Some(false),
        outbound_only: Some(false),
        accept_connections: Some(true),
        ..UdpConfig::default()
    });
    config
}

fn start_node(nsec: &str) -> Result<(), String> {
    let mut guard = NODE.lock().map_err(|_| "node lock poisoned".to_string())?;
    if guard.is_some() {
        return Err("FIPS node already running".to_string());
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .map_err(|e| format!("failed to build tokio runtime: {e}"))?;
    let endpoint = runtime
        .block_on(
            FipsEndpoint::builder()
                .config(mesh_config())
                .identity_nsec(nsec)
                .without_system_tun()
                .bind(),
        )
        .map_err(|e| format!("failed to bind FIPS endpoint: {e}"))?;
    let npub = endpoint.npub().to_string();
    let address = endpoint.address().to_ipv6().to_string();
    *guard = Some(EmbeddedNode {
        runtime,
        endpoint: Arc::new(endpoint),
        npub,
        address,
        tun_fd: None,
        stop: Arc::new(AtomicBool::new(false)),
        pumps: Vec::new(),
    });
    Ok(())
}

/// Owns `fd`. Spawns the two packet pumps: TUN reads feed the mesh, mesh
/// deliveries are written back to the TUN.
fn attach_tun_fd(fd: RawFd) -> Result<(), String> {
    if fd < 0 {
        return Err("invalid tun fd".to_string());
    }
    let mut guard = NODE.lock().map_err(|_| "node lock poisoned".to_string())?;
    let node = guard.as_mut().ok_or("FIPS node not running")?;
    if node.tun_fd.is_some() {
        return Err("tun fd already attached".to_string());
    }

    let stop = Arc::clone(&node.stop);
    let endpoint = Arc::clone(&node.endpoint);
    let outbound = std::thread::Builder::new()
        .name("fips-tun-read".into())
        .spawn(move || {
            // IPv6 minimum-MTU links still allow 65535-byte payloads after
            // reassembly; the TUN hands us whole packets, so size for the max.
            let mut buf = vec![0u8; 65535];
            loop {
                let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
                if n <= 0 {
                    // 0 = TUN torn down; <0 = read error (EBADF after stop()
                    // closes the fd). Either way the pump is done.
                    break;
                }
                if stop.load(Ordering::Relaxed) {
                    break;
                }
                let packet = &buf[..n as usize];
                // The TUN routes only fd00::/8, but Android still emits
                // occasional non-IPv6 noise (e.g. IPv4 link probes).
                if packet.first().map(|b| b >> 4) != Some(6) {
                    continue;
                }
                if endpoint.blocking_send_ip_packet(packet.to_vec()).is_err() {
                    break;
                }
            }
        })
        .map_err(|e| format!("failed to spawn tun read pump: {e}"))?;

    let stop = Arc::clone(&node.stop);
    let endpoint = Arc::clone(&node.endpoint);
    let handle = node.runtime.handle().clone();
    let inbound = std::thread::Builder::new()
        .name("fips-tun-write".into())
        .spawn(move || {
            loop {
                let Some(delivered) = handle.block_on(endpoint.recv_ip_packet()) else {
                    break;
                };
                if stop.load(Ordering::Relaxed) {
                    break;
                }
                let packet = delivered.packet;
                let n = unsafe { libc::write(fd, packet.as_ptr().cast(), packet.len()) };
                if n < 0 {
                    break;
                }
            }
        })
        .map_err(|e| format!("failed to spawn tun write pump: {e}"))?;

    node.tun_fd = Some(fd);
    node.pumps.push(outbound);
    node.pumps.push(inbound);
    Ok(())
}

fn stop_node() {
    let Ok(mut guard) = NODE.lock() else { return };
    let Some(node) = guard.take() else { return };
    node.stop.store(true, Ordering::Relaxed);
    // Shutting down the endpoint closes the delivered-packets channel, which
    // ends the write pump; closing the fd unblocks the read pump.
    let _ = node.runtime.block_on(node.endpoint.shutdown());
    if let Some(fd) = node.tun_fd {
        unsafe { libc::close(fd) };
    }
    for pump in node.pumps {
        let _ = pump.join();
    }
    node.runtime.shutdown_background();
}

fn state_json() -> String {
    let Ok(guard) = NODE.lock() else {
        return "{\"running\":false}".to_string();
    };
    let Some(node) = guard.as_ref() else {
        return "{\"running\":false}".to_string();
    };
    let peer_count = node
        .runtime
        .block_on(node.endpoint.peers())
        .map(|peers| peers.len())
        .unwrap_or(0);
    serde_json::json!({
        "running": true,
        "npub": node.npub,
        "address": node.address,
        "peerCount": peer_count,
    })
    .to_string()
}

fn json_error(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

fn to_jstring(env: &JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Starts the embedded node. Returns `{"npub":"...","address":"fd..."}` on
/// success or `{"error":"..."}`.
#[no_mangle]
pub extern "system" fn Java_com_greenart7c3_citrine_service_FipsNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    nsec: JString,
) -> jstring {
    let nsec: String = match env.get_string(&nsec) {
        Ok(value) => value.into(),
        Err(_) => return to_jstring(&env, &json_error("invalid nsec argument")),
    };
    let result = match start_node(&nsec) {
        Ok(()) => state_json(),
        Err(message) => json_error(&message),
    };
    to_jstring(&env, &result)
}

/// Attaches the VpnService TUN fd (ownership transfers to the bridge).
#[no_mangle]
pub extern "system" fn Java_com_greenart7c3_citrine_service_FipsNative_nativeAttachTunFd(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jboolean {
    match attach_tun_fd(fd as RawFd) {
        Ok(()) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

/// Returns `{"running":bool,"npub":...,"address":...,"peerCount":n}`.
#[no_mangle]
pub extern "system" fn Java_com_greenart7c3_citrine_service_FipsNative_nativeStateJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, &state_json())
}

/// Stops the node, closes the TUN fd, and joins the pumps.
#[no_mangle]
pub extern "system" fn Java_com_greenart7c3_citrine_service_FipsNative_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    stop_node();
}
