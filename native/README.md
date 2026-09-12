# Native bridge

Reserved for the Rust/JNI transport layer.

Planned boundary:

Android `UsbManager`
→ `UsbDeviceConnection`
→ native USB handle/FD bridge
→ `AndroidMtkPort`
→ `penumbra-mtk`

The current Android prototype is deliberately read-only and does not expose flashing, erase, partition writes, bootloader unlock, or exploit actions.
