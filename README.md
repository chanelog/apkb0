# OGTunnel — Android SSHWS & Xray VPN Client

OGTunnel is an independent, high-performance Android VPN client supporting user-provided SSHWS (SSH over WebSocket) and Xray (VLESS, VMess, Trojan) network configurations. It runs real tunneling protocols through Android `VpnService` with TUN interface packet routing.

---

## ⚠️ Important Notice
**OGTunnel does not provide VPN servers, accounts, subscriptions, or credentials.**
Users must supply or import their own private server configurations. No accounts, tracking, or advertisements are included.

---

## 🚀 Key Features

### 1. Protocol & Tunneling Engine
- **SSHWS (SSH over WebSocket)**:
  - SSH Direct TCP
  - SSH WebSocket without TLS (Port 80 / HTTP proxy)
  - SSH WebSocket with TLS (Port 443 / WSS / SNI spoofing)
  - SSH HTTP Upgrade payload macros (`[host]`, `[port]`, `[crlf]`, etc.)
  - Real SSH authentication via JSch with public key or password
  - Local SOCKS5 dynamic forwarder connected to SSH `direct-tcpip` channels
- **Xray Engine (VLESS, VMess, Trojan)**:
  - VLESS with flow (`xtls-rprx-vision`), TLS, and REALITY
  - VMess with WS, TCP, gRPC, and TLS
  - Trojan with WebSocket, gRPC, TLS
  - Compliant Xray JSON configuration generator
  - Dynamic ABI binary detection (`arm64-v8a`, `armeabi-v7a`, `x86_64`)
  - Crash detection, process monitoring, and graceful shutdown

### 2. Android VpnService & TUN Routing
- Establishes Linux virtual TUN interface (`10.8.0.2/24`, customizable MTU)
- Real IPv4 packet inspection with DNS resolution via protected socket
- Virtual TCP session dispatcher routing traffic into local SOCKS proxy
- Loopback prevention: automatically disallows the client's own package
- Split tunneling by application (Include Only / Bypass Selected apps)
- Persistent foreground service with notification and quick Disconnect action

### 3. Profile Management
- Separate and unified categories: All, SSHWS, Xray, Favorites
- Full CRUD: Add, Edit, Duplicate, Delete, Search, and Sort (Last Used, Name, Date)
- Smart import:
  - `vless://`
  - `vmess://` (standard Base64 JSON)
  - `trojan://`
  - `ssh://`
  - Raw Xray JSON configurations
- Backup and restore:
  - JSON backup with optional credential redaction for safe sharing

### 4. Network Diagnostics & Tools
- Live upload and download speed gauges (calculated from real transferred bytes)
- Sent and received byte counters
- Connection duration timer
- Real measured latency ping (TCP round-trip time)
- DNS Lookup Tool (custom domain resolution)
- TCP Reachability Tool (socket probe with millisecond latency)
- SSH Handshake & Authentication Probe (without bringing up full VPN)
- Xray Configuration Schema Validator

### 5. Diagnostics & Privacy
- Ring-buffer logging with live stream
- Automatic credential redaction (passwords, private keys, and UUIDs are safely masked)
- Log export and copy report
- Strict zero-telemetry: no external analytics, trackers, or hidden pings

---

## 🛠️ Build & Installation

### Requirements
- Android SDK 36 (minSdk 24, targetSdk 36)
- Java 11+
- Gradle 9.x+

### Build Commands
```bash
# Assemble Debug APK
gradle :app:assembleDebug

# Run Unit and Robolectric Tests
gradle :app:testDebugUnitTest
```

### Xray Core Deployment
OGTunnel is bundled with the official **Xray-core v26.9.30** release binaries:
- `arm64-v8a`: `app/src/main/jniLibs/arm64-v8a/libxray.so` (from `Xray-android-arm64-v8a.zip`)
- `x86_64`: `app/src/main/jniLibs/x86_64/libxray.so` (from `Xray-android-amd64.zip`)
- Asset routing data: `geoip.dat` and `geosite.dat` are bundled in `app/src/main/assets/` and dynamically loaded.
`XrayCoreManager` detects the device architecture and executes the native daemon directly with `useLegacyPackaging = true`.

---

## 🤖 GitHub Actions CI/CD Deployment

Workflow file: `.github/workflows/build-and-release.yml`

### Features:
- **Automatic Build**: Triggers on push / PR to `main` or `master`.
- **Manual Trigger**: Can be run manually via `Actions` -> `Run workflow` with choice of `debug`, `release`, or `all`.
- **Automated GitHub Releases**: Creating a git tag like `v1.0.0` will automatically build and publish APKs to GitHub Releases.
- **Signing Fallback**: If no release keystore secret is configured, it falls back to the debug keystore so CI builds never break.

### Optional GitHub Repository Secrets (for signed production APKs):
- `KEYSTORE_BASE64`: Base64-encoded `.jks` keystore file (`base64 -w 0 my-release-key.jks`)
- `STORE_PASSWORD`: Keystore store password
- `KEY_PASSWORD`: Key alias password

---

## 📄 License
- OGTunnel client UI and networking code: MIT License.
- Xray-core specifications: Mozilla Public License 2.0 (MPL 2.0).
