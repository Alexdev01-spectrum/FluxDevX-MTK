use std::fs::File;
use std::time::Duration;

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::jbyteArray;
use jni::JNIEnv;
use penumbra_mtk::port::{ConnectionType, MtkPort};
use penumbra_mtk::{DeviceBuilder, Error as PenumbraError, Result as PenumbraResult};

struct AndroidPort {
    vm: jni::JavaVM,
    connection: GlobalRef,
    in_endpoint: GlobalRef,
    out_endpoint: GlobalRef,
    timeout: Duration,
    connection_type: ConnectionType,
}

impl AndroidPort {
    fn env(&self) -> Result<jni::AttachGuard<'_>, String> {
        self.vm.attach_current_thread().map_err(|e| e.to_string())
    }

    fn bulk(&self, endpoint: &GlobalRef, data: &mut [u8], read: bool) -> Result<usize, String> {
        let mut env = self.env()?;
        let array = env.byte_array_from_slice(data).map_err(|e| e.to_string())?;
        let timeout = self.timeout.as_millis().clamp(100, 10_000) as i32;
        let result = env.call_method(
            self.connection.as_obj(),
            "bulkTransfer",
            "(Landroid/hardware/usb/UsbEndpoint;[BIII)I",
            &[
                JValue::Object(endpoint.as_obj()),
                JValue::Object(&array),
                JValue::Int(0),
                JValue::Int(data.len() as i32),
                JValue::Int(timeout),
            ],
        ).map_err(|e| e.to_string())?.i().map_err(|e| e.to_string())?;
        if result < 0 { return Err(format!("bulkTransfer failed ({result})")); }
        if read && result > 0 {
            let bytes = env.convert_byte_array(&array).map_err(|e| e.to_string())?;
            data[..result as usize].copy_from_slice(&bytes[..result as usize]);
        }
        Ok(result as usize)
    }

    fn io_error(message: String) -> penumbra_mtk::Error {
        PenumbraError::Io(std::io::Error::other(message))
    }
}

impl MtkPort for AndroidPort {
    fn open(&mut self) -> PenumbraResult<()> { Ok(()) }
    fn close(&mut self) -> PenumbraResult<()> { Ok(()) }
    fn reenumerate(&mut self, _vid: u16, _pid: u16) -> PenumbraResult<()> {
        Err(PenumbraError::Penumbra(penumbra_mtk::error::PenumbraError::UnsupportedDevice))
    }
    fn read_exact(&mut self, buf: &mut [u8]) -> PenumbraResult<usize> {
        let mut total = 0;
        while total < buf.len() {
            let endpoint = self.in_endpoint.clone();
            let n = self.bulk(&endpoint, &mut buf[total..], true).map_err(Self::io_error)?;
            if n == 0 { return Err(Self::io_error("USB read returned zero bytes".into())); }
            total += n;
        }
        Ok(total)
    }
    fn write_all(&mut self, buf: &[u8]) -> PenumbraResult<()> {
        let mut total = 0;
        while total < buf.len() {
            let endpoint = self.out_endpoint.clone();
            let mut chunk = buf[total..].to_vec();
            let n = self.bulk(&endpoint, &mut chunk, false).map_err(Self::io_error)?;
            if n == 0 { return Err(Self::io_error("USB write returned zero bytes".into())); }
            total += n;
        }
        Ok(())
    }
    fn flush(&mut self) -> PenumbraResult<()> { Ok(()) }
    fn get_baudrate(&self) -> u32 { 0 }
    fn get_port_name(&self) -> String { "Android USB MediaTek transport".into() }
    fn set_timeout(&mut self, timeout: Duration) -> PenumbraResult<()> { self.timeout = timeout; Ok(()) }
    fn get_timeout(&self) -> Duration { self.timeout }
    fn connection_type(&self) -> ConnectionType { self.connection_type }
    fn set_connection_type(&mut self, connection_type: ConnectionType) -> PenumbraResult<()> { self.connection_type = connection_type; Ok(()) }

    fn ctrl_out(&mut self, request_type: u8, request: u8, value: u16, index: u16, data: &[u8]) -> PenumbraResult<()> {
        let mut env = self.env().map_err(Self::io_error)?;
        let array = env.byte_array_from_slice(data).map_err(|e| Self::io_error(e.to_string()))?;
        let timeout = self.timeout.as_millis().clamp(100, 10_000) as i32;
        let result = env.call_method(
            self.connection.as_obj(), "controlTransfer", "(IIII[BII)I",
            &[
                JValue::Int(request_type as i32), JValue::Int(request as i32),
                JValue::Int(value as i32), JValue::Int(index as i32),
                JValue::Object(&array), JValue::Int(data.len() as i32), JValue::Int(timeout),
            ],
        ).map_err(|e| Self::io_error(e.to_string()))?.i().map_err(|e| Self::io_error(e.to_string()))?;
        if result < 0 { return Err(Self::io_error(format!("controlTransfer OUT failed ({result})"))); }
        Ok(())
    }

    fn ctrl_in(&mut self, request_type: u8, request: u8, value: u16, index: u16, len: usize) -> PenumbraResult<Vec<u8>> {
        let mut env = self.env().map_err(Self::io_error)?;
        let array = env.new_byte_array(len as i32).map_err(|e| Self::io_error(e.to_string()))?;
        let timeout = self.timeout.as_millis().clamp(100, 10_000) as i32;
        let result = env.call_method(
            self.connection.as_obj(), "controlTransfer", "(IIII[BII)I",
            &[
                JValue::Int(request_type as i32), JValue::Int(request as i32),
                JValue::Int(value as i32), JValue::Int(index as i32),
                JValue::Object(&array), JValue::Int(len as i32), JValue::Int(timeout),
            ],
        ).map_err(|e| Self::io_error(e.to_string()))?.i().map_err(|e| Self::io_error(e.to_string()))?;
        if result < 0 { return Err(Self::io_error(format!("controlTransfer IN failed ({result})"))); }
        let bytes = env.convert_byte_array(&array).map_err(|e| Self::io_error(e.to_string()))?;
        Ok(bytes[..result as usize].to_vec())
    }
}

fn connection_type(mode: i32) -> ConnectionType {
    match mode { 2 => ConnectionType::Da, 1 => ConnectionType::Preloader, _ => ConnectionType::Brom }
}

fn run_readback(env: &mut JNIEnv, connection: JObject, in_endpoint: JObject, out_endpoint: JObject, da: &[u8], auth: &[u8], mode: i32, partition: &str, output_path: &str) -> Result<(), String> {
    let vm = env.get_java_vm().map_err(|e| e.to_string())?;
    let port = AndroidPort {
        vm,
        connection: env.new_global_ref(connection).map_err(|e| e.to_string())?,
        in_endpoint: env.new_global_ref(in_endpoint).map_err(|e| e.to_string())?,
        out_endpoint: env.new_global_ref(out_endpoint).map_err(|e| e.to_string())?,
        timeout: Duration::from_secs(5),
        connection_type: connection_type(mode),
    };
    let mut device = DeviceBuilder::new(port).with_da_data(da).with_auth(auth).build().map_err(|e| e.to_string())?;
    device.init().map_err(|e| e.to_string())?;
    let mut output = File::create(output_path).map_err(|e| e.to_string())?;
    device.read_flash(partition, &mut output, |_done, _total| {}).map_err(|e| e.to_string())
}

fn run_flash(env: &mut JNIEnv, connection: JObject, in_endpoint: JObject, out_endpoint: JObject, da: &[u8], auth: &[u8], mode: i32, partition: &str, image_path: &str) -> Result<(), String> {
    let vm = env.get_java_vm().map_err(|e| e.to_string())?;
    let port = AndroidPort {
        vm,
        connection: env.new_global_ref(connection).map_err(|e| e.to_string())?,
        in_endpoint: env.new_global_ref(in_endpoint).map_err(|e| e.to_string())?,
        out_endpoint: env.new_global_ref(out_endpoint).map_err(|e| e.to_string())?,
        timeout: Duration::from_secs(5),
        connection_type: connection_type(mode),
    };
    let mut device = DeviceBuilder::new(port).with_da_data(da).with_auth(auth).build().map_err(|e| e.to_string())?;
    device.init().map_err(|e| e.to_string())?;
    let mut image = File::open(image_path).map_err(|e| e.to_string())?;
    device.write_flash(partition, &mut image, |_done, _total| {}).map_err(|e| e.to_string())
}

fn result_array(env: &mut JNIEnv, result: Result<(), String>) -> jbyteArray {
    let message = match result { Ok(()) => "OK".to_string(), Err(e) => format!("ERR:{e}") };
    env.byte_array_from_slice(message.as_bytes()).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fluxdevx_mtk_NativeBridge_nativeEcho(env: JNIEnv, _class: JClass, input: JByteArray) -> jbyteArray {
    match env.convert_byte_array(&input) { Ok(bytes) => env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()), Err(_) => std::ptr::null_mut() }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fluxdevx_mtk_NativeBridge_nativeReadPartition(mut env: JNIEnv, _class: JClass, connection: JObject, in_endpoint: JObject, out_endpoint: JObject, da: JByteArray, auth: JByteArray, mode: i32, partition: JString, output_path: JString) -> jbyteArray {
    let da = match env.convert_byte_array(&da) { Ok(v) => v, Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let auth = match env.convert_byte_array(&auth) { Ok(v) => v, Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let partition = match env.get_string(&partition) { Ok(v) => v.to_string_lossy().into_owned(), Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let output_path = match env.get_string(&output_path) { Ok(v) => v.to_string_lossy().into_owned(), Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let result = run_readback(&mut env, connection, in_endpoint, out_endpoint, &da, &auth, mode, &partition, &output_path);
    result_array(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fluxdevx_mtk_NativeBridge_nativeWritePartition(mut env: JNIEnv, _class: JClass, connection: JObject, in_endpoint: JObject, out_endpoint: JObject, da: JByteArray, auth: JByteArray, mode: i32, partition: JString, image_path: JString) -> jbyteArray {
    let da = match env.convert_byte_array(&da) { Ok(v) => v, Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let auth = match env.convert_byte_array(&auth) { Ok(v) => v, Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let partition = match env.get_string(&partition) { Ok(v) => v.to_string_lossy().into_owned(), Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let image_path = match env.get_string(&image_path) { Ok(v) => v.to_string_lossy().into_owned(), Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let result = run_flash(&mut env, connection, in_endpoint, out_endpoint, &da, &auth, mode, &partition, &image_path);
    result_array(&mut env, result)
}
