#ifdef __ANDROID__

#include <jni.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    void* data;
    size_t len;
    uint64_t token;
} xdm_buffer_t;

enum {
    XDM_OK = 0,
    XDM_E_INVALID = 1,
    XDM_E_NOT_FOUND = 2,
    XDM_E_PROTOCOL = 3,
    XDM_E_TIMEOUT = 4,
    XDM_E_STOPPED = 5,
    XDM_E_INTERNAL = 255
};

extern int xdm_engine_create(const uint8_t* config, size_t config_len, uint64_t* out_handle);
extern int xdm_engine_command(uint64_t handle, const uint8_t* data, size_t data_len);
extern int xdm_engine_next_frame(uint64_t handle, int timeout_ms, xdm_buffer_t* out);
extern int xdm_engine_platform_reply(uint64_t handle, const uint8_t* data, size_t data_len);
extern int xdm_engine_metadata(uint64_t handle, xdm_buffer_t* out);
extern int xdm_engine_shutdown(uint64_t handle);
extern void xdm_buffer_free(xdm_buffer_t buffer);

static int xgo_bytes(JNIEnv* env, jbyteArray array, jbyte** out_data, jsize* out_len) {
    *out_data = NULL;
    *out_len = 0;
    if (array == NULL) {
        return XDM_OK;
    }
    const jsize len = (*env)->GetArrayLength(env, array);
    if (len <= 0) {
        return XDM_OK;
    }
    jbyte* data = (*env)->GetByteArrayElements(env, array, NULL);
    if (data == NULL) {
        return XDM_E_INTERNAL;
    }
    *out_data = data;
    *out_len = len;
    return XDM_OK;
}

static void xgo_release_bytes(JNIEnv* env, jbyteArray array, jbyte* data) {
    if (array != NULL && data != NULL) {
        (*env)->ReleaseByteArrayElements(env, array, data, JNI_ABORT);
    }
}

static jobject xgo_buffer_result(JNIEnv* env, int status, xdm_buffer_t buffer) {
    jclass cls = (*env)->FindClass(
        env,
        "com/mikeyphw/xdm/android/engine/NativeCallBuffer"
    );
    if (cls == NULL) {
        if (buffer.token != 0) {
            xdm_buffer_free(buffer);
        }
        return NULL;
    }

    jmethodID ctor = (*env)->GetMethodID(
        env,
        cls,
        "<init>",
        "(ILjava/nio/ByteBuffer;J)V"
    );
    if (ctor == NULL) {
        if (buffer.token != 0) {
            xdm_buffer_free(buffer);
        }
        return NULL;
    }

    jobject byte_buffer = NULL;
    if (status == XDM_OK && buffer.data != NULL && buffer.len > 0) {
        byte_buffer = (*env)->NewDirectByteBuffer(env, buffer.data, (jlong)buffer.len);
        if (byte_buffer == NULL) {
            if (buffer.token != 0) {
                xdm_buffer_free(buffer);
            }
            return NULL;
        }
    }

    jobject result = (*env)->NewObject(
        env,
        cls,
        ctor,
        (jint)status,
        byte_buffer,
        (jlong)buffer.token
    );
    if (result == NULL && buffer.token != 0) {
        xdm_buffer_free(buffer);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeCreate(
    JNIEnv* env,
    jobject self,
    jbyteArray config,
    jlongArray out_handle
) {
    (void)self;
    if (out_handle == NULL || (*env)->GetArrayLength(env, out_handle) < 1) {
        return XDM_E_INVALID;
    }

    jbyte* bytes = NULL;
    jsize len = 0;
    int status = xgo_bytes(env, config, &bytes, &len);
    if (status != XDM_OK) {
        return status;
    }

    uint64_t handle = 0;
    status = xdm_engine_create((const uint8_t*)bytes, (size_t)len, &handle);
    xgo_release_bytes(env, config, bytes);

    if (status == XDM_OK) {
        const jlong value = (jlong)handle;
        (*env)->SetLongArrayRegion(env, out_handle, 0, 1, &value);
        if ((*env)->ExceptionCheck(env)) {
            return XDM_E_INTERNAL;
        }
    }
    return status;
}

JNIEXPORT jint JNICALL
Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeCommand(
    JNIEnv* env,
    jobject self,
    jlong handle,
    jbyteArray message
) {
    (void)self;
    jbyte* bytes = NULL;
    jsize len = 0;
    int status = xgo_bytes(env, message, &bytes, &len);
    if (status != XDM_OK) {
        return status;
    }
    status = xdm_engine_command((uint64_t)handle, (const uint8_t*)bytes, (size_t)len);
    xgo_release_bytes(env, message, bytes);
    return status;
}

JNIEXPORT jobject JNICALL
Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeNextFrame(
    JNIEnv* env,
    jobject self,
    jlong handle,
    jint timeout_ms
) {
    (void)self;
    xdm_buffer_t buffer = {0};
    const int status = xdm_engine_next_frame((uint64_t)handle, (int)timeout_ms, &buffer);
    return xgo_buffer_result(env, status, buffer);
}

JNIEXPORT jint JNICALL
Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativePlatformReply(
    JNIEnv* env,
    jobject self,
    jlong handle,
    jbyteArray message
) {
    (void)self;
    jbyte* bytes = NULL;
    jsize len = 0;
    int status = xgo_bytes(env, message, &bytes, &len);
    if (status != XDM_OK) {
        return status;
    }
    status = xdm_engine_platform_reply((uint64_t)handle, (const uint8_t*)bytes, (size_t)len);
    xgo_release_bytes(env, message, bytes);
    return status;
}

JNIEXPORT jobject JNICALL
Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeMetadata(
    JNIEnv* env,
    jobject self,
    jlong handle
) {
    (void)self;
    xdm_buffer_t buffer = {0};
    const int status = xdm_engine_metadata((uint64_t)handle, &buffer);
    return xgo_buffer_result(env, status, buffer);
}

JNIEXPORT jint JNICALL
Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_nativeShutdown(
    JNIEnv* env,
    jobject self,
    jlong handle
) {
    (void)env;
    (void)self;
    return xdm_engine_shutdown((uint64_t)handle);
}

JNIEXPORT void JNICALL
Java_com_mikeyphw_xdm_android_engine_JniNativeXdmCore_releaseBuffer(
    JNIEnv* env,
    jobject self,
    jlong token
) {
    (void)env;
    (void)self;
    xdm_buffer_t buffer = {0};
    buffer.token = (uint64_t)token;
    xdm_buffer_free(buffer);
}

#endif
