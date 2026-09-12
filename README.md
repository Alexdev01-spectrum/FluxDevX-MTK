# FluxDevX-MTK 🌘

Standalone Android MediaTek servicing/transport project.

## Current implementation

- Android USB Host enumeration and MediaTek VID/PID classification
- Android USB permission handling
- Bulk USB transport with full-write and exact-read helpers
- BROM/Preloader handshake implementation
- Read-only hardware/software/SOC/MEID/target-config identification
- User-supplied `auth_sv5.auth` loading with size validation and SHA-256 metadata
- User-supplied Download Agent loading with size validation
- Standard GPT header/partition-entry parsing
- MediaTek TXT/XML scatter parsing
- Scatter-driven partition selection and image-size validation
- Partition readback/flash orchestration with streaming I/O
- Protected-partition safeguards for direct erase/write planning
- Penumbra MTK core linked into the Rust/JNI bridge
- Android `UsbDeviceConnection` adapter for Penumbra's `MtkPort` interface
- Native partition readback and write entry points
- GitHub Actions Android build that cross-compiles the ARM64 native bridge

## Architecture

```text
Android UsbManager
       ↓
UsbDeviceConnection + claimed bulk endpoints
       ↓
Rust Android MtkPort adapter
       ↓
Penumbra MTK core
       ↓
BROM / Preloader
       ↓
Authorized DA V5/V6 session
       ↓
GPT / device partition table
       ↓
Scatter-selected partition
       ↓
Readback / Flash
```

The Android side owns the USB connection and endpoints. The Rust bridge calls Android's `UsbDeviceConnection.bulkTransfer()` and `controlTransfer()` through JNI, so Android USB permission remains authoritative.

## Scatter partition manager

The partition manager accepts MediaTek scatter files in both common **TXT** and **XML** representations. A partition is selected explicitly from the parsed scatter and a firmware image is checked against its declared capacity before flashing.

The native backend exposes Penumbra-backed read and write operations. Images are streamed from a temporary file into the native backend rather than copied into the native heap as one large byte array. Readback is written to a temporary file before the Android layer can export it to the selected destination.

## Auth and DA

`auth_sv5.auth` and DA files are treated as user-supplied authentication/servicing material. FluxDevX-MTK does not patch, forge, bypass, or manufacture authentication. A device requiring SLA/DAA must accept the supplied credentials through the legitimate protocol path.

The Penumbra integration uses its normal `DeviceBuilder` → `init()` → DA-mode → partition read/write flow. It does not enable Penumbra's exploit feature set.

## Build

The Android application uses the ARM64 Rust bridge. CI installs the Android NDK and Rust target, builds `libfluxdevx_mtk_bridge.so`, places it under `app/src/main/jniLibs/arm64-v8a/`, and then builds the APK.

For local native builds, use Rust stable with the `aarch64-linux-android` target and an Android NDK clang linker. The native bridge is intentionally kept separate from the Compose UI.

### Safety boundary

No exploit, SLA/DAA bypass, forged signature, FRP/IMEI manipulation, or arbitrary-memory-write interface is included. Destructive partition operations require explicit selection and validation, and authentication remains dependent on legitimate user-supplied DA/auth material.
