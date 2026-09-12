//! Small, platform-neutral transport primitives for the Android JNI bridge.
//! The Android layer owns UsbDeviceConnection; this module defines the byte
//! stream contract that a future Penumbra adapter can consume.

use std::io::{self, Read, Write};

pub trait MtkTransport {
    fn read_exact(&mut self, buf: &mut [u8]) -> io::Result<()>;
    fn write_all(&mut self, buf: &[u8]) -> io::Result<()>;
    fn flush(&mut self) -> io::Result<()>;
}

pub struct CallbackTransport<R, W, F> {
    reader: R,
    writer: W,
    flusher: F,
}

impl<R, W, F> CallbackTransport<R, W, F> {
    pub fn new(reader: R, writer: W, flusher: F) -> Self {
        Self { reader, writer, flusher }
    }
}

impl<R: Read, W: Write, F: FnMut() -> io::Result<()>> MtkTransport
    for CallbackTransport<R, W, F>
{
    fn read_exact(&mut self, buf: &mut [u8]) -> io::Result<()> {
        self.reader.read_exact(buf)
    }

    fn write_all(&mut self, buf: &[u8]) -> io::Result<()> {
        self.writer.write_all(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        (self.flusher)()
    }
}
