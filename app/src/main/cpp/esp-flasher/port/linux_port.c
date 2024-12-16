/* Copyright 2020 Espressif Systems (Shanghai) PTE LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "serial_io.h"
#include "serial_comm.h"
#include "linux_port.h"

#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <termios.h>
#include <time.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdarg.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/param.h>
#include <jni.h>

#include <android/log.h>

#define LOG_TAG "linux-port"

#define LOGD(...) do { __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGW(...) do { __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); } while(0)

// #define SERIAL_DEBUG_ENABLE

#ifdef SERIAL_DEBUG_ENABLE

static void serial_debug_print(const uint8_t *data, uint16_t size, bool write)
{
    static bool write_prev = false;
    uint8_t hex_str[3];

    // if (size > 8) size = 8; // only print length 8

    if (write_prev != write) {
        write_prev = write;
        printf("\n--- %s ---\n", write ? "WRITE" : "READ");
    }

    for (uint32_t i = 0; i < size; i++) {
        printf("%02x ", data[i]);
    }
}

#else

static void serial_debug_print(const uint8_t *data, uint16_t size, bool write) {}

#endif

static int serial;
static int64_t s_time_end;
static int32_t s_reset_trigger_pin;
static int32_t s_gpio0_trigger_pin;


/*
 * This is called by the VM when the shared library is first loaded.
 */

typedef union {
    JNIEnv *env;
    void *venv;
} UnionJNIEnvToVoid;

static UnionJNIEnvToVoid uenv;
static JavaVM *g_vm;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    uenv.venv = NULL;
    jint result = -1;
    JNIEnv *env = NULL;

    LOGI("JNI_OnLoad");
    g_vm = vm;

    if ((*vm)->GetEnv(vm, &uenv.venv, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("ERROR: GetEnv failed");
        goto bail;
    }
    env = uenv.env;
/*
    if (init_Exec(env) != JNI_TRUE) {
        LOGE("ERROR: init of Exec failed");
        goto bail;
    }

    if (init_FileCompat(env) != JNI_TRUE) {
        LOGE("ERROR: init of Exec failed");
        goto bail;
    }
*/
    result = JNI_VERSION_1_4;

    bail:
    return result;
}

static int get_env(JNIEnv **env) {
    int attached = 0;
    int status = (*g_vm)->GetEnv(g_vm, (void **) env, JNI_VERSION_1_4);
    if (status < 0) {
        LOGD("callback_handler:failed to get JNI environment assuming native thread");
        status = (*g_vm)->AttachCurrentThread(g_vm, env, NULL);
        if (status < 0) {
            LOGE("callback_handler: failed to attach current thread");
            return 0;
        }
        attached = 1;
    }
    return attached;
}

esp_loader_error_t loader_port_linux_init(const loader_linux_config_t *config) {
    return ESP_LOADER_SUCCESS;
}


esp_loader_error_t loader_port_serial_write(const uint8_t *data, uint16_t size, uint32_t timeout) {
    JNIEnv *env = NULL;
    int ret = 0;
    int attached = get_env(&env);
    if (NULL == env)
        return ESP_LOADER_ERROR_FAIL;
    jclass dpclazz = (*env)->FindClass(env, "com/pxs/terminal/JNI/ESP");
    if (dpclazz == 0) {
        LOGE("find class error %s", __FUNCTION__);
        return ESP_LOADER_ERROR_FAIL;
    }
    LOGD("find class %s", __FUNCTION__);
    //2 寻找class里面的方法
    jmethodID method1 = (*env)->GetStaticMethodID(env, dpclazz, "loaderPortSerialWrite", "([BII)I");
    if (method1 == 0) {
        LOGE("find method1 error %s", __FUNCTION__);
        return ESP_LOADER_ERROR_FAIL;
    }
    LOGD("find method1 %s", __FUNCTION__);
    //3 .调用这个方法
    jbyteArray mdata = (*env)->NewByteArray(env, size);
    (*env)->SetByteArrayRegion(env, mdata, 0, size, (const jbyte *) data);
    jint msize = size;
    jint mtimeout = timeout;
    ret = (*env)->CallStaticIntMethod(env, dpclazz, method1, mdata, msize, mtimeout);
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ret;
}


esp_loader_error_t loader_port_serial_read(uint8_t *data, uint16_t size, uint32_t timeout) {
    int ret = 0;
    JNIEnv *env = NULL;
    int attached = get_env(&env);
    if (NULL == env)
        return ESP_LOADER_ERROR_FAIL;
    jclass dpclazz = (*env)->FindClass(env, "com/pxs/terminal/JNI/ESP");
    if (dpclazz == 0) {
        LOGE("find class error %s", __FUNCTION__);
        return ESP_LOADER_ERROR_FAIL;
    }
    LOGD("find class %s", __FUNCTION__);
    //2 寻找class里面的方法
    jmethodID method1 = (*env)->GetStaticMethodID(env, dpclazz, "loaderPortSerialRead", "([BII)I");
    if (method1 == 0) {
        LOGE("find method1 error %s", __FUNCTION__);
        return ESP_LOADER_ERROR_FAIL;
    }
    LOGD("find method1 %s", __FUNCTION__);
    //3 .调用这个方法
    jbyteArray mdata = (*env)->NewByteArray(env, size);
    jint msize = size;
    jint mtimeout = timeout;
    ret = (*env)->CallStaticIntMethod(env, dpclazz, method1, mdata, msize, mtimeout);
    jbyte *rdata = (*env)->GetByteArrayElements(env, mdata, 0);
    memcpy(data, rdata, size);
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ret;
}


// Set GPIO0 LOW, then assert reset pin for 50 milliseconds.
void loader_port_enter_bootloader(void) {
    JNIEnv *env = NULL;
    int attached = get_env(&env);
    if (NULL == env)
        return;
    jclass dpclazz = (*env)->FindClass(env, "com/pxs/terminal/JNI/ESP");
    if (dpclazz == 0) {
        LOGE("find class error %s", __FUNCTION__);
        return;
    }
    LOGD("find class %s", __FUNCTION__);
    //2 寻找class里面的方法
    jmethodID method1 = (*env)->GetStaticMethodID(env, dpclazz, "loaderPortEnterBootloader", "()V");
    if (method1 == 0) {
        LOGE("find method1 error %s", __FUNCTION__);
        return;
    }
    LOGD("find method1 %s", __FUNCTION__);
    //3 .调用这个方法
    (*env)->CallStaticVoidMethod(env, dpclazz, method1);
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}


void loader_port_reset_target(void) {
    JNIEnv *env = NULL;
    int attached = get_env(&env);
    if (NULL == env)
        return;
    jclass dpclazz = (*env)->FindClass(env, "com/pxs/terminal/JNI/ESP");
    if (dpclazz == 0) {
        LOGE("find class error %s", __FUNCTION__);
        return;
    }
    LOGD("find class %s", __FUNCTION__);
    //2 寻找class里面的方法
    jmethodID method1 = (*env)->GetStaticMethodID(env, dpclazz, "loaderPortResetTarget", "()V");
    if (method1 == 0) {
        LOGE("find method1 error %s", __FUNCTION__);
        return;
    }
    LOGD("find method1 %s", __FUNCTION__);
    //3 .调用这个方法
    (*env)->CallStaticVoidMethod(env, dpclazz, method1);
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}


void loader_port_delay_ms(uint32_t ms) {
    usleep(ms * 1000);
}


void loader_port_start_timer(uint32_t ms) {
    s_time_end = clock() + (ms * (CLOCKS_PER_SEC / 1000));
}


uint32_t loader_port_remaining_time(void) {
    int64_t remaining = (s_time_end - clock()) / 1000;
    return (remaining > 0) ? (uint32_t) remaining : 0;
}


void loader_port_debug_print(const char *str) {
    LOGI("DEBUG: %s", str);
}

esp_loader_error_t loader_port_change_baudrate(uint32_t baudrate) {
    int ret = 0;
    JNIEnv *env = NULL;
    int attached = get_env(&env);
    if (NULL == env)
        return ESP_LOADER_ERROR_FAIL;
    jclass dpclazz = (*env)->FindClass(env, "com/pxs/terminal/JNI/ESP");
    if (dpclazz == 0) {
        LOGE("find class error %s", __FUNCTION__);
        return ESP_LOADER_ERROR_FAIL;
    }
    LOGD("find class %s", __FUNCTION__);
    //2 寻找class里面的方法
    //   jmethodID   (*GetMethodID)(JNIEnv*, jclass, const char*, const char*);
    jmethodID method1 = (*env)->GetStaticMethodID(env, dpclazz, "loaderPortChangeBaudrate", "(I)I");
    if (method1 == 0) {
        LOGE("find method1 error %s", __FUNCTION__);
        return ESP_LOADER_ERROR_FAIL;
    }
    LOGD("find method1 %s", __FUNCTION__);
    //3 .调用这个方法
    //    void        (*CallVoidMethod)(JNIEnv*, jobject, jmethodID, ...);
    ret = (*env)->CallStaticIntMethod(env, dpclazz, method1, (int) baudrate);
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ret;
}

struct CallBack {
    jobject callbackObj;
    jmethodID OnFlashProcess;
    jmethodID OnError;
};

static struct CallBack callBack = {0};

esp_loader_error_t loader_port_callback_on_flash_process(int progress) {
    int ret = 0;
    JNIEnv *env = NULL;
    int attached = 0;
    if (callBack.OnFlashProcess) {
        attached = get_env(&env);
        (*env)->CallVoidMethod(env, callBack.callbackObj, callBack.OnFlashProcess, progress);
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ret;
}

esp_loader_error_t loader_port_callback_on_error(int err_code) {
    int ret = 0;
    JNIEnv *env = NULL;
    int attached = 0;
    if (callBack.OnFlashProcess) {
        attached = get_env(&env);
        (*env)->CallVoidMethod(env, callBack.callbackObj, callBack.OnError, err_code);
    }
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ret;
}


esp_loader_error_t loader_port_set_listener(jobject listener) {
    int ret = 0;
    JNIEnv *env = NULL;
    int attached = 0;
    attached = get_env(&env);
    if (NULL == env) {
        ret = ESP_LOADER_ERROR_FAIL;
        goto out;
    }
    if (NULL == listener) {
        ret = ESP_LOADER_ERROR_INVALID_PARAM;
        goto out;
    }
    jclass objClass = (*env)->GetObjectClass(env, listener);
    callBack.callbackObj = (*env)->NewGlobalRef(env, listener);//创建对象的本地变量
    // 获取方法ID
    callBack.OnFlashProcess = (*env)->GetMethodID(env, objClass, "OnFlashProcess", "(I)V");
    callBack.OnError = (*env)->GetMethodID(env, objClass, "OnError", "(I)V");
    if (NULL == callBack.OnFlashProcess || NULL == callBack.OnError) {
        ret = ESP_LOADER_ERROR_FAIL;
        goto out;
    }
    out:
    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return ret;
}