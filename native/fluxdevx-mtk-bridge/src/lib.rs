use std::fs::File;
use std::io::{Read, Write};
use std::time::Duration;

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JValue};
use jni::sys::jbyteArray;
use jni::JNIEnv;
use penumbra_mtk::port::{ConnectionType, MtkPort};
use penumbra_mtk::{DeviceBuilder, Result as PenumbraResult};

struct AndroidPort {
    vm: jni::JavaVM,
    connection: GlobalRef,
    in_endpoint: GlobalRef,
    out_endpoint: GlobalRef,
    timeout: Duration,
    connection_type: ConnectionType,
}

impl AndroidPort {
    fn env(&self) -> Result<JNIEnv<'_>, String> {
        self.vm.attach_current_thread().map_err(|e| e.to_string())
    }

    fn bulk(&mut self, endpoint: &GlobalRef, data: &mut [u8], read: bool) -> Result<usize, String> {
        let mut env = self.env()?;
        let array = env.byte_array_from_slice(data).map_err(|e| e.to_string())?;
        let timeout = self.timeout.as_millis().clamp(100, 10_000) as i32;
        let result = env
            .call_method(
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
            )
            .map_err(|e| e.to_string())?
            .i()
            .map_err(|e| e.to_string())?;
        if result < 0 {
            return Err(format!("UsbDeviceConnection.bulkTransfer failed ({result})"));
        }
        if read && result > 0 {
            env.get_byte_array_region(&array, 0, unsafe {
                std::slice::from_raw_parts_mut(data.as_mut_ptr() as *mut i8, result as usize)
            })
            .map_err(|e| e.to_string())?;
        }
        Ok(result as usize)
    }

    fn control_in(&mut self, request_type: u8, request: u8, value: u16, index: u16, len: usize) -> Result<Vec<u8>, String> {
        let mut env = self.env()?;
        let array = env.new_byte_array(len as i32).map_err(|e| e.to_string())?;
        let timeout = self.timeout.as_millis().clamp(100, 10_000) as i32;
        let result = env.call_method(
            self.connection.as_obj(),
            "controlTransfer",
            "(IIII[BII)I",
            &[
                JValue::Int(request_type as i32),
                JValue::Int(request as i32),
                JValue::Int(value as i32),
                JValue::Int(index as i32),
                JValue::Object(&array),
                JValue::Int(len as i32),
                JValue::Int(timeout),
            ],
        ).map_err(|e| e.to_string())?.i().map_err(|e| e.to_string())?;
        if result < 0 { return Err(format!("controlTransfer failed ({result})")); }
        let mut out = vec![0u8; result as usize];
        env.convert_byte_array(&array).map(|bytes| out.copy_from_slice(&bytes[..out.len()])).map_err(|e| e.to_string())?;
        Ok(out)
    }
}

impl MtkPort for AndroidPort {
    fn open(&mut self) -> PenumbraResult<()> { Ok(()) }
    fn close(&mut self) -> PenumbraResult<()> { Ok(()) }
    fn reenumerate(&mut self, _vid: u16, _pid: u16) -> PenumbraResult<()> {
        Err(penumbra_mtk::Error::UnsupportedDevice)
    }

    fn read_exact(&mut self, buf: &mut [u8]) -> PenumbraResult<usize> {
        let mut total = 0;
        while total < buf.len() {
            let n = self.bulk(&self.in_endpoint.clone(), &mut buf[total..], true)
                .map_err(|e| penumbra_mtk::Error::Other(e.into()))?;
            if n == 0 { return Err(penumbra_mtk::Error::Other("USB read returned zero bytes".into())); }
            total += n;
        }
        Ok(total)
    }

    fn write_all(&mut self, buf: &[u8]) -> PenumbraResult<()> {
        let mut total = 0;
        while total < buf.len() {
            let mut chunk = buf[total..].to_vec();
            let n = self.bulk(&self.out_endpoint.clone(), &mut chunk, false)
                .map_err(|e| penumbra_mtk::Error::Other(e.into()))?;
            if n == 0 { return Err(penumbra_mtk::Error::Other("USB write returned zero bytes".into())); }
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
        let mut env = self.env().map_err(|e| penumbra_mtk::Error::Other(e.into()))?;
        let array = env.byte_array_from_slice(data).map_err(|e| penumbra_mtk::Error::Other(e.to_string().into()))?;
        let timeout = self.timeout.as_millis().clamp(100, 10_000) as i32;
        let result = env.call_method(
            self.connection.as_obj(), "controlTransfer", "(IIII[BII)I",
            &[
                JValue::Int(request_type as i32), JValue::Int(request as i32),
                JValue::Int(value as i32), JValue::Int(index as i32),
                JValue::Object(&array), JValue::Int(data.len() as i32), JValue::Int(timeout),
            ],
        ).map_err(|e| penumbra_mtk::Error::Other(e.to_string().into()))?.i()
            .map_err(|e| penumbra_mtk::Error::Other(e.to_string().into()))?;
        if result < 0 { return Err(penumbra_mtk::Error::Other(format!("controlTransfer OUT failed ({result})").into())); }
        Ok(())
    }

    fn ctrl_in(&mut self, request_type: u8, request: u8, value: u16, index: u16, len: usize) -> PenumbraResult<Vec<u8>> {
        self.control_in(request_type, request, value, index, len)
            .map_err(|e| penumbra_mtk::Error::Other(e.into()))
    }
}

fn connection_type(mode: i32) -> ConnectionType {
    match mode {
        2 => ConnectionType::Da,
        1 => ConnectionType::Preloader,
        _ => ConnectionType::Brom,
    }
}

fn run_readback(
    env: &mut JNIEnv,
    connection: JObject,
    in_endpoint: JObject,
    out_endpoint: JObject,
    da: &[u8],
    auth: &[u8],
    mode: i32,
    partition: &str,
    output_path: &str,
) -> Result<(), String> {
    let vm = env.get_java_vm().map_err(|e| e.to_string())?;
    let port = AndroidPort {
        vm,
        connection: env.new_global_ref(connection).map_err(|e| e.to_string())?,
        in_endpoint: env.new_global_ref(in_endpoint).map_err(|e| e.to_string())?,
        out_endpoint: env.new_global_ref(out_endpoint).map_err(|e| e.to_string())?,
        timeout: Duration::from_secs(5),
        connection_type: connection_type(mode),
    };

    let mut device = DeviceBuilder::new(port)
        .with_da_data(da)
        .with_auth(auth)
        .build()
        .map_err(|e| e.to_string())?;
    device.init().map_err(|e| e.to_string())?;

    let mut output = File::create(output_path).map_err(|e| e.to_string())?;
    device.read_flash(partition, &mut output, |_done, _total| {})
        .map_err(|e| e.to_string())
}

fn run_flash(
    env: &mut JNIEnv,
    connection: JObject,
    in_endpoint: JObject,
    out_endpoint: JObject,
    da: &[u8],
    auth: &[u8],
    mode: i32,
    partition: &str,
    image_path: &str,
) -> Result<(), String> {
    let vm = env.get_java_vm().map_err(|e| e.to_string())?;
    let port = AndroidPort {
        vm,
        connection: env.new_global_ref(connection).map_err(|e| e.to_string())?,
        in_endpoint: env.new_global_ref(in_endpoint).map_err(|e| e.to_string())?,
        out_endpoint: env.new_global_ref(out_endpoint).map_err(|e| e.to_string())?,
        timeout: Duration::from_secs(5),
        connection_type: connection_type(mode),
    };

    let mut device = DeviceBuilder::new(port)
        .with_da_data(da)
        .with_auth(auth)
        .build()
        .map_err(|e| e.to_string())?;
    device.init().map_err(|e| e.to_string())?;

    let mut image = File::open(image_path).map_err(|e| e.to_string())?;
    device.write_flash(partition, &mut image, |_done, _total| {})
        .map_err(|e| e.to_string())
}

fn result_array(env: &mut JNIEnv, result: Result<(), String>) -> jbyteArray {
    match result {
        Ok(()) => env.byte_array_from_slice(b"OK").map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => env.byte_array_from_slice(format!("ERR:{e}").as_bytes()).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fluxdevx_mtk_NativeBridge_nativeEcho(
    mut env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    match env.convert_byte_array(&input) {
        Ok(bytes) => env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fluxdevx_mtk_NativeBridge_nativeReadPartition(
    mut env: JNIEnv,
    _class: JClass,
    connection: JObject,
    in_endpoint: JObject,
    out_endpoint: JObject,
    da: JByteArray,
    auth: JByteArray,
    mode: i32,
    partition: jni::objects::JString,
    output_path: jni::objects::JString,
) -> jbyteArray {
    let da = match env.convert_byte_array(&da) { Ok(v) => v, Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let auth = match env.convert_byte_array(&auth) { Ok(v) => v, Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let partition = match env.get_string(&partition) { Ok(v) => v.to_string_lossy().into_owned(), Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let output_path = match env.get_string(&output_path) { Ok(v) => v.to_string_lossy().into_owned(), Err(e) => return result_array(&mut env, Err(e.to_string())) };
    result_array(&mut env, run_readback(&mut env, connection, in_endpoint, out_endpoint, &da, &auth, mode, &partition, &output_path))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fluxdevx_mtk_NativeBridge_nativeWritePartition(
    mut env: JNIEnv,
    _class: JClass,
    connection: JObject,
    in_endpoint: JObject,
    out_endpoint: JObject,
    da: JByteArray,
    auth: JByteArray,
    mode: i32,
    partition: jni::objects::JString,
    image_path: jni::objects::JString,
) -> jbyteArray {
    let da = match env.convert_byte_array(&da) { Ok(v) => v, Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let auth = match env.convert_byte_array(&auth) { Ok(v) => v, Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let partition = match env.get_string(&partition) { Ok(v) => v.to_string_lossy().into_owned(), Err(e) => return result_array(&mut env, Err(e.to_string())) };
    let image_path = match env.get_string(&image_path) { Ok(v) => v.to_string_lossy().into_owned(), Err(e) => return result_array(&mut env, Err(e.to_string())) };
    result_array(&mut env, run_flash(&mut env, connection, in_endpoint, out_endpoint, &da, &auth, mode, &partition, &image_path))
}
