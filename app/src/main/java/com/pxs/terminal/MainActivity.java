package com.pxs.terminal;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pxs.terminal.databinding.ActivityMainBinding;
import com.pxs.terminal.util.ProcessShell;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Used to load the 'terminal' library on application startup.
    static {
        System.loadLibrary("terminal");
    }

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Example of a call to a native method
        TextView tv = binding.sampleText;
        tv.setText(stringFromJNI());
        /*
        new File(getFilesDir(), "home").mkdirs();
        runCommand("cp /sdcard/busybox /data/data/com.pxs.terminal/files/home/busybox");
        runCommand("chmod 755 /data/data/com.pxs.terminal/files/home/busybox");
        runCommand("/data/data/com.pxs.terminal/files/home/busybox");
        new Thread(() -> {
            runCommand("cd /data/data/com.pxs.terminal/files/home;./busybox telnetd -l /system/bin/sh -p 2121 -F");
            // ProcessShell.exec("cd /data/data/com.pxs.terminal/files/home;./busybox telnetd -l /system/bin/sh -p 2121 -F", false);
            runCommand("ps -ef");
        }).start();
        */
        // ProcessShell.exec("cd /data/data/com.pxs.terminal;pwd;./busybox", false);
        // createSubprocess();
        /*
        new File(getFilesDir(), "home").mkdirs();
        File file = new File("/data/data/com.pxs.terminal/files/home/busybox");
        file.setReadable(true);
        file.setWritable(true);
        file.setExecutable(true);
        */
        // test();
    }

    private void serialStart()
    {
    }

    // "/data/data/com.termux/files/usr/bin/login", TermuxConstants.TERMUX_HOME_DIR_PATH, new String[]{"-login"}, environment, this
    private String mShellPath = "/system/bin/sh";
    private String mCwd = "/data/data/com.pxs.terminal/files/home";
    private String[] mArgs = new String[]{"-"};
    private String[] mEnv = new String[3];

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }

    void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    void test() {

        String path = System.getenv("PATH");
        mEnv[0] = "TERM=" + "screen";
        mEnv[1] = "PATH=" + path;
        mEnv[2] = "HOME=" + mCwd;

        int columns = 80;
        int rows = 24;
        int[] processId = new int[1];
        int mTerminalFileDescriptor = createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns);
        int mShellPid = processId[0];
        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        Log.i(TAG, "run: read:" + new String(buffer, 0, read));
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    termOut.write("./busybox telnetd -l /system/bin/sh -p 2121 -F\r".getBytes(StandardCharsets.UTF_8));
                    while (true) {
                        termOut.write("ps -ef\r".getBytes(StandardCharsets.UTF_8));
                        MainActivity.this.sleep(3000);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = waitFor(mShellPid);
                Log.w(TAG, "run: exit " + processExitCode);
            }
        }.start();
    }

    /**
     * A native method that is implemented by the 'terminal' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();

    /**
     * Create a subprocess. Differs from {@link ProcessBuilder} in that a pseudoterminal is used to communicate with the
     * subprocess.
     * <p/>
     * Callers are responsible for calling {@link #close(int)} on the returned file descriptor.
     *
     * @param cmd       The command to execute
     * @param cwd       The current working directory for the executed command
     * @param args      An array of arguments to the command
     * @param envVars   An array of strings of the form "VAR=value" to be added to the environment of the process
     * @param processId A one-element array to which the process ID of the started process will be written.
     * @return the file descriptor resulting from opening /dev/ptmx master device. The sub process will have opened the
     * slave device counterpart (/dev/pts/$N) and have it as stdint, stdout and stderr.
     */
    public native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns);

    /**
     * Set the window size for a given pty, which allows connected programs to learn how large their screen is.
     */
    public native void setPtyWindowSize(int fd, int rows, int cols);

    /**
     * Causes the calling thread to wait for the process associated with the receiver to finish executing.
     *
     * @return if >= 0, the exit status of the process. If < 0, the signal causing the process to stop negated.
     */
    public native int waitFor(int processId);

    /**
     * Close a file descriptor through the close(2) system call.
     */
    public native void close(int fileDescriptor);

    /*
    public native int LoaderPortLinuxInit(String device, int baudrate);
    public native int ConnectToTarget(int higrerBaudrate);
    public native void ResetTarget();
    public native int FlashBinary(byte[] bin, int address);
*/

    public static void runCommand(String command) {
        Process process = null;
        DataOutputStream dataOutputStream = null;
        DataInputStream dataInputStream = null;
        DataInputStream dataErrStream = null;
        StringBuffer wifiConf = new StringBuffer();
        try {
            process = Runtime.getRuntime().exec("sh -");
            dataOutputStream = new DataOutputStream(process.getOutputStream());
            dataInputStream = new DataInputStream(process.getInputStream());
            dataErrStream = new DataInputStream(process.getErrorStream());
            dataOutputStream
                    .writeBytes(command + "\n");
            dataOutputStream.flush();
            dataOutputStream.writeBytes("exit\n");
            dataOutputStream.flush();
            InputStreamReader inputStreamReader = new InputStreamReader(
                    dataInputStream, "UTF-8");
            BufferedReader bufferedReader = new BufferedReader(
                    inputStreamReader);
            String line = null;
            while ((line = bufferedReader.readLine()) != null) {
                wifiConf.append(line);
            }
            bufferedReader.close();
            inputStreamReader.close();
            bufferedReader = null;
            inputStreamReader = null;
            inputStreamReader = new InputStreamReader(
                    dataErrStream, "UTF-8");
            bufferedReader = new BufferedReader(
                    inputStreamReader);
            line = null;
            while ((line = bufferedReader.readLine()) != null) {
                wifiConf.append(line + "\n");
            }
            bufferedReader.close();
            inputStreamReader.close();
            process.waitFor();
            Log.d("shell命令执行结果：", wifiConf.toString() + " " + process.exitValue() + "");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (dataOutputStream != null) {
                    dataOutputStream.close();
                }
                if (dataInputStream != null) {
                    dataInputStream.close();
                }
                process.destroy();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}