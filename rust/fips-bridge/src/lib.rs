//! JNI bridge running an embedded FIPS (fips.network) node inside Citrine.
//!
//! Tor-style, no VPN: the FIPS endpoint delivers raw IPv6 packets, a smoltcp
//! stack ([`proxy`]) terminates TCP at the node's fd00::/8 address in
//! userspace, and accepted connections are proxied to the relay's loopback
//! port. The Kotlin side (`com.greenart7c3.citrine.service.FipsNative`) only
//! starts and stops the node. One node per process.

mod proxy;

use std::sync::{Arc, Mutex};

use fips_endpoint::{Config, FipsEndpoint, NostrDiscoveryPolicy, TransportInstances, UdpConfig};
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use tokio::sync::mpsc;

struct EmbeddedNode {
    runtime: tokio::runtime::Runtime,
    endpoint: Arc<FipsEndpoint>,
    npub: String,
    address: String,
    tasks: Vec<tokio::task::JoinHandle<()>>,
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

fn start_node(nsec: &str, port: u16) -> Result<(), String> {
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
    let ipv6 = endpoint.address().to_ipv6();
    let endpoint = Arc::new(endpoint);

    // Mesh packets → smoltcp stack → mesh packets, with the stack terminating
    // TCP for the relay in userspace.
    let (packet_in_tx, packet_in_rx) = mpsc::channel::<Vec<u8>>(64);
    let (packet_out_tx, mut packet_out_rx) = mpsc::channel::<Vec<u8>>(64);

    let mut tasks = Vec::with_capacity(3);
    let rx_endpoint = Arc::clone(&endpoint);
    tasks.push(runtime.spawn(async move {
        while let Some(delivered) = rx_endpoint.recv_ip_packet().await {
            let packet = delivered.packet;
            if packet.first().map(|b| b >> 4) != Some(6) {
                continue;
            }
            if packet_in_tx.send(packet).await.is_err() {
                break;
            }
        }
    }));
    let tx_endpoint = Arc::clone(&endpoint);
    tasks.push(runtime.spawn(async move {
        while let Some(packet) = packet_out_rx.recv().await {
            if tx_endpoint.send_ip_packet(packet).await.is_err() {
                break;
            }
        }
    }));
    tasks.push(proxy::spawn_proxy(
        runtime.handle(),
        packet_in_rx,
        packet_out_tx,
        ipv6,
        port,
    ));

    *guard = Some(EmbeddedNode {
        runtime,
        endpoint,
        npub,
        address: ipv6.to_string(),
        tasks,
    });
    Ok(())
}

fn stop_node() {
    let Ok(mut guard) = NODE.lock() else { return };
    let Some(node) = guard.take() else { return };
    // Shutting down the endpoint closes the delivered-packets channel, which
    // unwinds the pumps and the proxy through the packet channels.
    let _ = node.runtime.block_on(node.endpoint.shutdown());
    for task in node.tasks {
        task.abort();
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

/// Starts the embedded node, terminating mesh TCP for `port` in userspace and
/// proxying to `127.0.0.1:port`. Returns
/// `{"running":true,"npub":"...","address":"fd..","peerCount":0}` or
/// `{"error":"..."}`.
#[no_mangle]
pub extern "system" fn Java_com_greenart7c3_citrine_service_FipsNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    nsec: JString,
    port: jint,
) -> jstring {
    let nsec: String = match env.get_string(&nsec) {
        Ok(value) => value.into(),
        Err(_) => return to_jstring(&env, &json_error("invalid nsec argument")),
    };
    if !(1..=65535).contains(&port) {
        return to_jstring(&env, &json_error("invalid port argument"));
    }
    let result = match start_node(&nsec, port as u16) {
        Ok(()) => state_json(),
        Err(message) => json_error(&message),
    };
    to_jstring(&env, &result)
}

/// Returns `{"running":bool,"npub":...,"address":...,"peerCount":n}`.
#[no_mangle]
pub extern "system" fn Java_com_greenart7c3_citrine_service_FipsNative_nativeStateJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, &state_json())
}

/// Stops the node and all its tasks.
#[no_mangle]
pub extern "system" fn Java_com_greenart7c3_citrine_service_FipsNative_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    stop_node();
}
