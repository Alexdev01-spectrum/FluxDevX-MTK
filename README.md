# FluxDevX-MTK 🌘

Standalone Android MediaTek servicing/transport project.

## Current implementation

- Android USB Host enumeration and MediaTek VID/PID classification
- Android USB permission handling
- Bulk USB transport with full-write and exact-read helpers
- BROM/Preloader handshake implementation
- Read-only hardware/software/SOC/MEID/target-config identification
- User-supplied `auth_sv5.auth` loading with size validation and SHA-256 metadata
- User-supplied DA loading with size validation
- Standard GPT header/partition-entry parsing
- Partition image size validation
- Explicit destructive-operation confirmation helpers
- Protected-partition safeguards for direct erase/write planning
- Rust/JNI bridge scaffold

## Architecture

```text
Android UsbManager
       ↓
UsbDeviceConnection
       ↓
UsbBulkTransport
       ↓
MtkProtocol
       ↓
BROM / Preloader
       ↓
Authorized DA session (next backend layer)
       ↓
GPT / partition operations
```

The Android side owns `UsbDeviceConnection`. The native layer must not retain a borrowed file descriptor after that connection is closed.

## Auth and DA

`auth_sv5.auth` and DA files are treated as user-supplied authentication/servicing material. FluxDevX-MTK does not patch, forge, bypass, or manufacture authentication. A device requiring SLA/DAA must accept the supplied credentials through the legitimate protocol path.

## Planned backend stages

1. USB transport — implemented
2. Read-only BROM/Preloader identification — implemented
3. Penumbra core integration and Android transport adapter
4. Authenticated DA V5/V6 upload using a user-supplied authorized DA/auth pair
5. DA session lifecycle and GPT retrieval
6. Partition read/backup
7. Partition write/erase with confirmation, size checks, progress and cancellation

Penumbra documents DA-mode partition listing, partition read/write, and erase operations, plus XML DA upload/download and progress-report flows.

### Safety boundary

No exploit, SLA/DAA bypass, forged signature, FRP/IMEI manipulation, or arbitrary-memory-write interface is included. Destructive partition operations will require explicit confirmation and validation.
