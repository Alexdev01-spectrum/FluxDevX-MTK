use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;

/// JNI smoke-test boundary. USB ownership remains on Android until the
/// native transport contract is finalized; no raw fd is retained here yet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_fluxdevx_mtk_NativeBridge_nativeEcho(
    mut env: jni::JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    match env.convert_byte_array(&input) {
        Ok(bytes) => env.byte_array_from_slice(&bytes)
            .map(|array| array.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}
