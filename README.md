# FluxDevX-MTK 🌘

Standalone Android MediaTek transport research project.

## v0.2 — USB probe milestone

- Android USB Host enumeration
- MediaTek VID/PID classification
- Android USB permission request
- USB device open/close
- Interface enumeration
- Endpoint/type/max-packet inspection
- Rust/JNI bridge scaffold

## Architecture

```text
Android UsbManager
       ↓
UsbDeviceConnection
       ↓
Android USB transport
       ↓
JNI boundary
       ↓
AndroidMtkPort
       ↓
penumbra-mtk
       ↓
MTK protocol
```

The design follows Penumbra's transport abstraction instead of duplicating its MTK protocol implementation. The Android side owns `UsbDeviceConnection`; the native layer must not retain a borrowed file descriptor after that connection is closed.

## Development stages

1. USB enumeration and endpoint inspection — implemented
2. Android transport adapter and JNI lifecycle — next
3. Read-only BROM/Preloader identification
4. Penumbra core integration
5. DA session and partition metadata
6. Flash/backup UI only after transport tests are stable

### Safety boundary

The current prototype intentionally exposes **no flashing, erase, partition-write, bootloader-unlock, or exploit action**. Read-only communication and transport validation come first.
