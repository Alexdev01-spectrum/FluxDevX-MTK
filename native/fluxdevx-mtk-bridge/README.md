# FluxDevX-MTK Rust bridge

Minimal JNI boundary for the Android transport.

Next transport contract:
1. Pass only safe USB metadata/handles across JNI.
2. Implement Android bulk/control transfer adapters.
3. Map those operations onto Penumbra's `MtkPort` abstraction.
4. Add read-only device identification tests before any write path.

The bridge must not assume that an Android `ParcelFileDescriptor` remains valid after its owning `UsbDeviceConnection` is closed.
