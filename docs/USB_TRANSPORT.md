# FluxDevX-MTK USB Transport

## Goal

Provide an Android USB transport layer that can eventually satisfy the Penumbra `MtkPort` abstraction without modifying the MTK protocol implementation.

## Android side

`UsbManager` discovers devices and `requestPermission()` obtains temporary user-authorized access. `UsbManager.openDevice()` returns a `UsbDeviceConnection`, which is kept private to the transport session.

The transport is responsible for:

- selecting the correct USB interface
- selecting bulk/control endpoints
- reading exact byte counts
- writing complete buffers
- control transfers
- timeout handling
- close/re-enumeration handling

## Native boundary

The initial JNI boundary is deliberately small. Android owns USB permission and the Java/Kotlin layer owns the `UsbDeviceConnection`. Native code should receive only the operations it needs rather than assuming desktop `nusb` APIs exist on Android.

## Safety boundary

This project separates transport development from destructive operations. The transport milestone must be validated with enumeration and read-only protocol identification before adding partition writes, erase, or exploit-triggering functionality.

## Penumbra adapter

The intended adapter is conceptually:

```text
Penumbra MtkPort
    |
    +-- AndroidMtkPort
            |
            +-- JNI bridge
                    |
                    +-- Android UsbDeviceConnection
```

No proprietary RaffXLink implementation is copied. RaffXLink APK reverse engineering is used only as an architectural reference for the Android USB/JNI approach.
