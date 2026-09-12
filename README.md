# FluxDevX-MTK 🌘

Standalone Android MediaTek transport and servicing project.

## v0.3 — authenticated transport preparation

- Android USB Host enumeration
- MediaTek VID/PID classification
- Android USB permission request
- USB device open/close
- Interface and endpoint inspection
- Android bulk transport layer
- Rust/JNI bridge scaffold
- User-supplied `auth_sv5.auth` file picker and validation
- Authentication material kept opaque until a legitimate backend consumes it

## Authentication files

FluxDevX-MTK accepts manufacturer/vendor-provided MediaTek `.auth` / `auth_sv5.auth` files through Android's document picker. The selected file is loaded as bytes with basic size/empty-file validation.

An auth file is **not** a security bypass by itself. The backend must use the file only for the device's supported authentication protocol and report authentication failures cleanly.

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
MTK protocol / legitimate authentication
```

## Planned servicing stages

1. USB enumeration and endpoint inspection — implemented
2. Android bulk transport — implemented
3. Read-only BROM/Preloader identification
4. Penumbra core integration
5. Legitimate DA session + supplied authentication material
6. GPT/partition metadata
7. Partition backup/read
8. Validated partition write
9. Validated partition erase
10. Progress, cancellation, logs, and recovery handling

Security-bypass exploits are intentionally outside the project scope. Devices requiring SLA/DAA authentication should use an appropriate authorized authentication file or vendor-supported authorization path.
