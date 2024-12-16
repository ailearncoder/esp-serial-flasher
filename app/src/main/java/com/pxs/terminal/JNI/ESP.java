package com.pxs.terminal.JNI;

import android.util.Log;

import com.pxs.terminal.LinkActivity;

public class ESP {
    public static LinkActivity linkActivity = null;
    // Used to load the 'terminal' library on application startup.
    static {
        System.loadLibrary("terminal");
    }

    private static final String TAG = "ESP";
    public enum EspLoaderErr {
        ESP_LOADER_SUCCESS,                /*!< Success */
        ESP_LOADER_ERROR_FAIL,             /*!< Unspecified error */
        ESP_LOADER_ERROR_TIMEOUT,          /*!< Timeout elapsed */
        ESP_LOADER_ERROR_IMAGE_SIZE,       /*!< Image size to flash is larger than flash size */
        ESP_LOADER_ERROR_INVALID_MD5,      /*!< Computed and received MD5 does not match */
        ESP_LOADER_ERROR_INVALID_PARAM,    /*!< Invalid parameter passed to function */
        ESP_LOADER_ERROR_INVALID_TARGET,   /*!< Connected target is invalid */
        ESP_LOADER_ERROR_UNSUPPORTED_CHIP, /*!< Attached chip is not supported */
        ESP_LOADER_ERROR_UNSUPPORTED_FUNC, /*!< Function is not supported on attached target */
        ESP_LOADER_ERROR_INVALID_RESPONSE  /*!< Internal error */
    }

    public interface OnFlashListener {
        void OnFlashProcess(int percent);
        void OnError(int errCode);
    }

    // esp_loader_error_t loader_port_change_baudrate(uint32_t baudrate)
    public static int loaderPortChangeBaudrate(int baudrate) {
        Log.i(TAG, "loaderPortChangeBaudrate: ");
        if(null == linkActivity){
            Log.e(TAG, "loaderPortSerialRead: null activity");
        }
        return linkActivity.loaderPortChangeBaudrate(baudrate);
    }

    public static void loaderPortResetTarget() {
        Log.i(TAG, "loaderPortResetTarget: ");
        if(null == linkActivity){
            Log.e(TAG, "loaderPortSerialRead: null activity");
        }
        linkActivity.loaderPortResetTarget();
    }

    public static void loaderPortEnterBootloader() {
        Log.i(TAG, "loaderPortEnterBootloader: ");
        if(null == linkActivity){
            Log.e(TAG, "loaderPortSerialRead: null activity");
        }
        linkActivity.loaderPortEnterBootloader();
    }

    public static int loaderPortSerialRead(byte[] data, int size, int timeout) {
        Log.d(TAG, "loaderPortSerialRead: ");
        if(null == linkActivity){
            Log.e(TAG, "loaderPortSerialRead: null activity");
            return 1;
        }
        return linkActivity.loaderPortSerialRead(data, size, timeout);
    }

    public static int loaderPortSerialWrite(byte[] data, int size, int timeout) {
        Log.d(TAG, "loaderPortSerialWrite: ");
        if(null == linkActivity){
            Log.e(TAG, "loaderPortSerialRead: null activity");
            return 1;
        }
        return linkActivity.loaderPortSerialWrite(data, size, timeout);
    }

    public static native int LoaderPortLinuxInit(String device, int baudrate);
    public static native int ConnectToTarget(int higrerBaudrate);
    public static native void ResetTarget();
    public static native int FlashBinary(byte[] bin, int address, OnFlashListener listener);
    public static native int EraseFlash();
}
