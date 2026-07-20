//! Userspace TCP termination for the embedded FIPS node — the Tor-hidden-service
//! model, no TUN/VpnService involved.
//!
//! The FIPS mesh delivers raw IPv6 packets addressed to this node. A smoltcp
//! `Interface` terminates TCP at `[fd-addr]:port` here in userspace, and every
//! accepted connection is byte-spliced to `127.0.0.1:port`, where the relay's
//! Ktor server listens. Reply packets emitted by the stack are handed back to
//! the mesh. The module is endpoint-agnostic: packets flow through plain
//! channels, which also makes it testable on the host without a FIPS node.

use std::collections::{HashMap, VecDeque};
use std::net::Ipv6Addr;

use smoltcp::iface::{Config, Interface, PollResult, SocketHandle, SocketSet};
use smoltcp::phy::{Checksum, ChecksumCapabilities, Device, DeviceCapabilities, Medium};
use smoltcp::socket::tcp;
use smoltcp::time::{Duration as SmolDuration, Instant as SmolInstant};
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpListenEndpoint};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

/// Matches the FIPS UDP transport default (IPv6 minimum MTU).
const MTU: usize = 1280;

/// Max concurrent mesh connections; each closed socket is re-listened.
const LISTENER_POOL: usize = 16;

const RX_BUF: usize = 16 * 1024;
const TX_BUF: usize = 32 * 1024;
const CHUNK: usize = 16 * 1024;

/// Idle mesh connections are reaped after this long.
const SOCKET_TIMEOUT_SECS: u64 = 300;

/// In-memory packet queues standing in for a network device. `rx` is fed from
/// the mesh before each poll; `tx` is drained to the mesh after each poll.
struct QueueDevice {
    rx: VecDeque<Vec<u8>>,
    tx: Vec<Vec<u8>>,
}

struct RxTok(Vec<u8>);

impl smoltcp::phy::RxToken for RxTok {
    fn consume<R, F: FnOnce(&[u8]) -> R>(self, f: F) -> R {
        f(&self.0)
    }
}

struct TxTok<'a>(&'a mut Vec<Vec<u8>>);

impl smoltcp::phy::TxToken for TxTok<'_> {
    fn consume<R, F: FnOnce(&mut [u8]) -> R>(self, len: usize, f: F) -> R {
        let mut buf = vec![0u8; len];
        let result = f(&mut buf);
        self.0.push(buf);
        result
    }
}

impl Device for QueueDevice {
    type RxToken<'a> = RxTok;
    type TxToken<'a> = TxTok<'a>;

    fn receive(&mut self, _now: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let packet = self.rx.pop_front()?;
        Some((RxTok(packet), TxTok(&mut self.tx)))
    }

    fn transmit(&mut self, _now: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(TxTok(&mut self.tx))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = MTU;
        // Peer-computed checksums travel the mesh unmodified, so verify on rx
        // and compute on tx (the defaults).
        caps.checksum = ChecksumCapabilities::default();
        caps.checksum.ipv4 = Checksum::None;
        caps
    }
}

/// Splice-side state for one established mesh connection.
struct Conn {
    /// Stack → upstream relay. Bytes are only pulled from the smoltcp socket
    /// when this has capacity, so the mesh peer's TCP window closes naturally.
    down_tx: mpsc::Sender<Vec<u8>>,
    /// Upstream relay → stack.
    up_rx: mpsc::Receiver<Vec<u8>>,
    /// Chunk partially accepted by `send_slice`, resumed on the next turn.
    pending: Option<(Vec<u8>, usize)>,
    upstream_done: bool,
    peer_fin_seen: bool,
}

/// Bridges one accepted mesh connection to the relay's loopback listener.
async fn splice_upstream(
    port: u16,
    up_tx: mpsc::Sender<Vec<u8>>,
    mut down_rx: mpsc::Receiver<Vec<u8>>,
    wake: mpsc::Sender<()>,
) {
    let Ok(mut stream) = tokio::net::TcpStream::connect(("127.0.0.1", port)).await else {
        // Dropping both channel ends tears the mesh connection down.
        return;
    };
    let (mut read_half, mut write_half) = stream.split();
    let mut buf = vec![0u8; CHUNK];
    loop {
        tokio::select! {
            n = read_half.read(&mut buf) => {
                match n {
                    // EOF/error: dropping up_tx below signals the stack to FIN
                    // the mesh peer; remaining peer bytes drain afterwards.
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if up_tx.send(buf[..n].to_vec()).await.is_err() {
                            break;
                        }
                        let _ = wake.try_send(());
                    }
                }
            }
            chunk = down_rx.recv() => {
                match chunk {
                    Some(chunk) => {
                        if write_half.write_all(&chunk).await.is_err() {
                            break;
                        }
                        // Channel capacity freed: nudge the event loop to pull
                        // more bytes out of the smoltcp socket.
                        let _ = wake.try_send(());
                    }
                    None => {
                        let _ = write_half.shutdown().await;
                        break;
                    }
                }
            }
        }
    }
    drop(up_tx);
    // Forward any remaining relay→peer bytes? The relay half-close case is
    // rare for WebSockets; finish the write side then let everything drop.
    while let Some(chunk) = down_rx.recv().await {
        if write_half.write_all(&chunk).await.is_err() {
            break;
        }
    }
    let _ = write_half.shutdown().await;
    let _ = wake.try_send(());
}

/// Runs the smoltcp event loop until `packet_in` closes.
async fn event_loop(
    mut packet_in: mpsc::Receiver<Vec<u8>>,
    packet_out: mpsc::Sender<Vec<u8>>,
    addr: Ipv6Addr,
    port: u16,
) {
    let mut dev = QueueDevice { rx: VecDeque::new(), tx: Vec::new() };
    let mut iface = Interface::new(Config::new(HardwareAddress::Ip), &mut dev, SmolInstant::now());
    iface.update_ip_addrs(|addrs| {
        let _ = addrs.push(IpCidr::new(IpAddress::Ipv6(addr.into()), 128));
    });
    // A default route must exist so replies to off-link mesh peers dispatch;
    // the gateway value itself is unused on an IP-medium device.
    let _ = iface.routes_mut().add_default_ipv6_route(addr.into());

    let mut sockets = SocketSet::new(vec![]);
    let mut handles: Vec<SocketHandle> = Vec::with_capacity(LISTENER_POOL);
    let listen_endpoint = IpListenEndpoint { addr: Some(IpAddress::Ipv6(addr.into())), port };
    for _ in 0..LISTENER_POOL {
        let mut socket = tcp::Socket::new(
            tcp::SocketBuffer::new(vec![0u8; RX_BUF]),
            tcp::SocketBuffer::new(vec![0u8; TX_BUF]),
        );
        socket.set_nagle_enabled(false);
        socket.set_timeout(Some(SmolDuration::from_secs(SOCKET_TIMEOUT_SECS)));
        let _ = socket.listen(listen_endpoint);
        handles.push(sockets.add(socket));
    }

    let mut conns: HashMap<SocketHandle, Conn> = HashMap::new();
    let (wake_tx, mut wake_rx) = mpsc::channel::<()>(1);

    loop {
        while let Ok(packet) = packet_in.try_recv() {
            dev.rx.push_back(packet);
        }

        let now = SmolInstant::now();
        let _: PollResult = iface.poll(now, &mut dev, &mut sockets);

        for packet in dev.tx.drain(..) {
            if packet_out.send(packet).await.is_err() {
                return;
            }
        }

        for &handle in &handles {
            let socket = sockets.get_mut::<tcp::Socket>(handle);

            // Spawn the upstream splice only once the handshake completes.
            // Before Established (e.g. SynReceived) may_recv() is false, which
            // the FIN detection below would misread as a peer close.
            if socket.state() == tcp::State::Established && !conns.contains_key(&handle) {
                let (up_tx, up_rx) = mpsc::channel::<Vec<u8>>(4);
                let (down_tx, down_rx) = mpsc::channel::<Vec<u8>>(4);
                tokio::spawn(splice_upstream(port, up_tx, down_rx, wake_tx.clone()));
                conns.insert(
                    handle,
                    Conn { down_tx, up_rx, pending: None, upstream_done: false, peer_fin_seen: false },
                );
            }

            let Some(conn) = conns.get_mut(&handle) else {
                continue;
            };

            // Mesh peer → relay: pull only when the channel has room, so the
            // socket buffer fills and the TCP window closes toward the peer.
            while socket.can_recv() {
                match conn.down_tx.try_reserve() {
                    Ok(permit) => {
                        let mut chunk = vec![0u8; CHUNK];
                        match socket.recv_slice(&mut chunk) {
                            Ok(n) if n > 0 => {
                                chunk.truncate(n);
                                permit.send(chunk);
                            }
                            _ => break,
                        }
                    }
                    Err(_) => break,
                }
            }

            // Relay → mesh peer, resuming any partially written chunk first.
            loop {
                if let Some((chunk, offset)) = conn.pending.take() {
                    match socket.send_slice(&chunk[offset..]) {
                        Ok(n) if offset + n < chunk.len() => {
                            conn.pending = Some((chunk, offset + n));
                            break;
                        }
                        Ok(_) => {}
                        Err(_) => break,
                    }
                }
                if !socket.can_send() {
                    break;
                }
                match conn.up_rx.try_recv() {
                    Ok(chunk) => conn.pending = Some((chunk, 0)),
                    Err(mpsc::error::TryRecvError::Disconnected) => {
                        conn.upstream_done = true;
                        break;
                    }
                    Err(mpsc::error::TryRecvError::Empty) => break,
                }
            }
            if conn.up_rx.is_closed() && conn.up_rx.is_empty() && conn.pending.is_none() {
                conn.upstream_done = true;
            }

            if conn.upstream_done && conn.pending.is_none() {
                // Relay finished (or refused the connection): FIN the peer.
                socket.close();
            }
            if !socket.may_recv() && !conn.peer_fin_seen && !socket.is_listening() {
                // Peer sent FIN (or the connection died): half-close upstream
                // by replacing the sender, which drops the channel.
                conn.peer_fin_seen = true;
                let (closed_tx, _) = mpsc::channel::<Vec<u8>>(1);
                conn.down_tx = closed_tx;
            }

            if socket.state() == tcp::State::Closed {
                conns.remove(&handle);
                socket.abort();
                let _ = socket.listen(listen_endpoint);
            }
        }

        let delay = iface
            .poll_delay(now, &sockets)
            .map(|d| std::time::Duration::from_micros(d.total_micros()))
            .unwrap_or(std::time::Duration::from_secs(1))
            .min(std::time::Duration::from_secs(1));
        tokio::select! {
            packet = packet_in.recv() => {
                match packet {
                    Some(packet) => dev.rx.push_back(packet),
                    None => return,
                }
            }
            _ = wake_rx.recv() => {}
            _ = tokio::time::sleep(delay) => {}
        }
    }
}

/// Spawns the userspace TCP proxy on `handle`. Packets from the mesh go into
/// `packet_in`; packets for the mesh come out of `packet_out`.
pub fn spawn_proxy(
    handle: &tokio::runtime::Handle,
    packet_in: mpsc::Receiver<Vec<u8>>,
    packet_out: mpsc::Sender<Vec<u8>>,
    addr: Ipv6Addr,
    port: u16,
) -> JoinHandle<()> {
    handle.spawn(event_loop(packet_in, packet_out, addr, port))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::time::{Duration, Instant};

    /// Minimal mesh-peer stand-in: a second smoltcp stack wired back-to-back
    /// to the proxy's packet channels through plain Vec<u8> frames.
    struct TestClient {
        dev: QueueDevice,
        iface: Interface,
        sockets: SocketSet<'static>,
        handle: SocketHandle,
    }

    impl TestClient {
        fn new(addr: Ipv6Addr) -> Self {
            let mut dev = QueueDevice { rx: VecDeque::new(), tx: Vec::new() };
            let mut iface =
                Interface::new(Config::new(HardwareAddress::Ip), &mut dev, SmolInstant::now());
            iface.update_ip_addrs(|addrs| {
                let _ = addrs.push(IpCidr::new(IpAddress::Ipv6(addr.into()), 128));
            });
            let _ = iface.routes_mut().add_default_ipv6_route(addr.into());
            let mut sockets = SocketSet::new(vec![]);
            let socket = tcp::Socket::new(
                tcp::SocketBuffer::new(vec![0u8; RX_BUF]),
                tcp::SocketBuffer::new(vec![0u8; TX_BUF]),
            );
            let handle = sockets.add(socket);
            Self { dev, iface, sockets, handle }
        }

        fn socket(&mut self) -> &mut tcp::Socket<'static> {
            self.sockets.get_mut::<tcp::Socket>(self.handle)
        }

        /// One poll turn: exchange packets with the proxy channels.
        fn pump(&mut self, to_proxy: &mpsc::Sender<Vec<u8>>, from_proxy: &mut mpsc::Receiver<Vec<u8>>) {
            while let Ok(packet) = from_proxy.try_recv() {
                self.dev.rx.push_back(packet);
            }
            self.iface.poll(SmolInstant::now(), &mut self.dev, &mut self.sockets);
            for packet in self.dev.tx.drain(..) {
                to_proxy.blocking_send(packet).expect("proxy packet channel closed");
            }
        }
    }

    fn spawn_echo_server() -> u16 {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { continue };
                std::thread::spawn(move || {
                    let mut buf = [0u8; 4096];
                    loop {
                        match stream.read(&mut buf) {
                            Ok(0) | Err(_) => break,
                            Ok(n) => {
                                if stream.write_all(&buf[..n]).is_err() {
                                    break;
                                }
                            }
                        }
                    }
                });
            }
        });
        port
    }

    #[test]
    fn proxies_mesh_tcp_to_loopback() {
        let port = spawn_echo_server();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .unwrap();
        let (to_proxy, proxy_in) = mpsc::channel::<Vec<u8>>(64);
        let (proxy_out, mut from_proxy) = mpsc::channel::<Vec<u8>>(64);
        let server_addr: Ipv6Addr = "fd00::1".parse().unwrap();
        let client_addr: Ipv6Addr = "fd00::2".parse().unwrap();
        let _proxy = spawn_proxy(runtime.handle(), proxy_in, proxy_out, server_addr, port);

        let mut client = TestClient::new(client_addr);

        // A few sequential connections exercise accept, bidirectional data,
        // and graceful teardown from a pooled listener socket.
        for round in 0..3u16 {
            let payload = format!("hello fips {round}").into_bytes();
            {
                let context = client.iface.context();
                client
                    .sockets
                    .get_mut::<tcp::Socket>(client.handle)
                    .connect(context, (IpAddress::Ipv6(server_addr.into()), port), 40000 + round)
                    .expect("connect");
            }

            let deadline = Instant::now() + Duration::from_secs(10);
            let mut sent = false;
            let mut received = Vec::new();
            let mut ticks = 0u64;
            loop {
                assert!(Instant::now() < deadline, "round {round}: timed out (got {received:?})");
                client.pump(&to_proxy, &mut from_proxy);
                let socket = client.socket();
                if !sent && socket.can_send() {
                    socket.send_slice(&payload).expect("send");
                    sent = true;
                }
                if socket.can_recv() {
                    let mut chunk = [0u8; 4096];
                    let n = socket.recv_slice(&mut chunk).expect("recv");
                    received.extend_from_slice(&chunk[..n]);
                }
                if received == payload {
                    break;
                }
                std::thread::sleep(Duration::from_millis(2));
            }

            // Tear down: the client FINs, and the proxy must FIN back once its
            // upstream splice closes. TimeWait/Closed both mean the peer's FIN
            // arrived (smoltcp lingers in TimeWait for 2*MSL before Closed).
            client.socket().close();
            let deadline = Instant::now() + Duration::from_secs(10);
            loop {
                let state = client.socket().state();
                if matches!(state, tcp::State::TimeWait | tcp::State::Closed) {
                    break;
                }
                assert!(Instant::now() < deadline, "round {round}: close timed out (state {state:?})");
                client.pump(&to_proxy, &mut from_proxy);
                std::thread::sleep(Duration::from_millis(2));
            }

            // Fresh client socket for the next round (the old one is in TimeWait).
            let socket = tcp::Socket::new(
                tcp::SocketBuffer::new(vec![0u8; RX_BUF]),
                tcp::SocketBuffer::new(vec![0u8; TX_BUF]),
            );
            client.handle = client.sockets.add(socket);
        }
    }
}
