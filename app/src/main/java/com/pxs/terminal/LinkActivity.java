package com.pxs.terminal;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.Menu;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.navigation.NavigationView;

import androidx.annotation.NonNull;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.pxs.terminal.JNI.ESP;
import com.pxs.terminal.databinding.ActivityLinkBinding;
import com.pxs.terminal.ui.home.HomeFragment;
import com.pxs.terminal.util.CustomProber;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class LinkActivity extends AppCompatActivity implements SerialInputOutputManager.Listener {

    private AppBarConfiguration mAppBarConfiguration;
    private ActivityLinkBinding binding;
    private UsbSerialPort usbSerialPort;
    private boolean connected;
    private int baudRate = 115200;

    private static final String TAG = "LinkActivity";
    private SerialInputOutputManager usbIoManager;

    public LinkActivity() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    if (usbPermission == UsbPermission.Granted) {
//                        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
//                        if (LinkActivity.this.deviceId == usbDevice.getDeviceId()) {
//                            connect();
//                        }
                        Toast.makeText(LinkActivity.this, "已授权访问，请再次点击开启端口监听！", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(LinkActivity.this, "USB设备权限被拒绝，请重启app再次授权", Toast.LENGTH_SHORT).show();
                        if (listener != null) {
                            listener.disConnected();
                        }
                    }
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityLinkBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.appBarLink.toolbar);
        binding.appBarLink.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action " + binding.navView.getCheckedItem().getTitle(), Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });
        DrawerLayout drawer = binding.drawerLayout;
        NavigationView navigationView = binding.navView;
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow)
                .setOpenableLayout(drawer)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_link);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);

        /*********************************************************************************************/
        ESP.linkActivity = this;
        binding.appBarLink.content.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (ESP.ConnectToTarget(115200) == 0) {
                            Log.i(TAG, "run: connect ok");
                            File file = new File("/sdcard/firmware.bin");
                            long len = file.length();
                            byte bin[] = new byte[(int) len];
                            FileInputStream inputStream = null;
                            try {
                                inputStream = new FileInputStream(file);
                                int readn = inputStream.read(bin);
                                if (readn == bin.length) {
                                    ESP.FlashBinary(bin, 0x10000, new ESP.OnFlashListener() {
                                        @Override
                                        public void OnFlashProcess(int percent) {
                                            System.out.println("OnFlashProcess:" + percent);
                                        }

                                        @Override
                                        public void OnError(int errCode) {
                                            System.out.println("OnError:" + errCode);
                                        }
                                    });
                                    ESP.ResetTarget();
                                } else {
                                    Log.w(TAG, "run: readn " + readn + " != " + bin.length);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "run: read firmware.bin error ");
                            }
                            try {
                                if (inputStream != null) {
                                    inputStream.close();
                                }
                                inputStream = null;
                            } catch (Exception e) {

                            }
                        }
                    }
                }).start();
            }
        });
        startService(new Intent(this, SerialService.class));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.link, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_link);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
    }

    public int deviceId = 0;
    public int port = 0;
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private final BroadcastReceiver broadcastReceiver;
    private LinkedBlockingQueue<Byte> serialQueue = new LinkedBlockingQueue<>();

    @Override
    public void onNewData(byte[] data) {
        for (byte item : data) {
            serialQueue.add(item);
        }
        Log.d(TAG, data.length + ": onNewData: " + new String(data, 0, Math.min(data.length, 36)));
    }

    @Override
    public void onRunError(Exception e) {
        serialQueue.clear();
        e.printStackTrace();
    }

    private enum UsbPermission {Unknown, Requested, Granted, Denied}

    private UsbPermission usbPermission = UsbPermission.Unknown;
    private final Handler mainLooper;

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));
//        if(usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
//            mainLooper.post(this::connect);
    }

    @Override
    protected void onPause() {
        if (connected) {
            status("disconnected");
            disconnect();
        }
        unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    private void status(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    public HomeFragment.OnDeivceLinkListener listener = null;

    public void connect() {
        connect(baudRate);
    }

    /*
     * Serial + UI
     */
    public void connect(int baudRate) {
        serialQueue.clear();
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < port) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(port);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(INTENT_ACTION_GRANT_USB), 0);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }
        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            // usbSerialPort.setRTS(true);
            // usbSerialPort.setDTR(true);
            usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
            usbIoManager.start();
            status("connected");
            connected = true;
            if (listener != null) {
                listener.connected();
            }
            // controlLines.start();
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    /*
     * Serial + UI
     */
    public void connect(int deviceId, int port, int baudRate) {
        this.deviceId = deviceId;
        this.port = port;
        serialQueue.clear();
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < port) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(port);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(INTENT_ACTION_GRANT_USB), 0);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }
        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            // usbSerialPort.setRTS(true);
            // usbSerialPort.setDTR(true);
            status("connected");
            connected = true;
            if (listener != null) {
                listener.connected();
            }
            connected = false;
            // controlLines.start();
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    public void disconnect() {
        connected = false;
        /*
        controlLines.stop();
        */
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {
        }
        usbSerialPort = null;
        if (listener != null) {
            listener.disConnected();
        }
    }

    public int loaderPortChangeBaudrate(final int baudRate) {
        int ret = 1;
        if (connected) {
            try {
                /*
                final boolean[] waitover = {false};
                runOnUiThread(() -> {
                    disconnect();
                    connect(baudrate);
                    waitover[0] = true;
                });
                while (!waitover[0]) {
                    Thread.sleep(10);
                }
                if (connected) {
                    ret = 0;
                }
                */
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
                ret = 0;
            } catch (Exception e) {
                e.printStackTrace();
                Log.w(TAG, "loaderPortChangeBaudrate " + baudRate + ": error");
            }
        }
        return ret;
    }

    public void loaderPortResetTarget() {
        if (connected) {
            // self._setRTS(True)  # EN->LOW
            // time.sleep(0.1)
            // self._setRTS(False)
            try {
                usbSerialPort.setRTS(true);
                Thread.sleep(100);
                usbSerialPort.setRTS(false);
            } catch (Exception e) {
                e.printStackTrace();
                Log.w(TAG, "loaderPortResetTarget: error");
            }
        }
    }

    boolean esp32r0_delay = false;

    public void loaderPortEnterBootloader() {
        if (connected) {
            try {
                usbSerialPort.setDTR(false); // IO0=HIGH
                usbSerialPort.setRTS(true);   // EN=LOW, chip in reset
                Thread.sleep(100);
                if (esp32r0_delay) {
                    // Some chips are more likely to trigger the esp32r0
                    // watchdog reset silicon bug if they 're held with EN=LOW
                    // for a longer period
                    Thread.sleep(1200);
                }
                usbSerialPort.setDTR(true);   // IO0=LOW
                usbSerialPort.setRTS(false);  // EN=HIGH, chip out of reset
                if (esp32r0_delay) {
                    // Sleep longer after reset.
                    // This workaround only works on revision 0 ESP32 chips,
                    // it exploits a silicon bug spurious watchdog reset.
                    Thread.sleep(400);  // allow watchdog reset to occur
                }
                Thread.sleep(50);
                usbSerialPort.setDTR(false);  // IO0=HIGH, done
            } catch (Exception e) {
                e.printStackTrace();
                Log.w(TAG, "loaderPortEnterBootloader: error");
            }
        }
    }

    public int loaderPortSerialRead(byte[] data, int size, int timeout) {
        if (null == data || 0 == size) {
            Log.e(TAG, "loaderPortSerialRead: data:" + data + " size" + size);
            return 1;
        }
        if (connected) {
            int i = 0;
            Byte tmp = null;
            for (i = 0; i < size; i++) {
                try {
                    tmp = serialQueue.poll(timeout, TimeUnit.MILLISECONDS);
                    if (null != tmp) {
                        data[i] = tmp;
                    } else {
                        break;
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                }
            }
            if (i == size) return 0;
            else {
                Log.w(TAG, "timeout=" + timeout + " loaderPortSerialRead: " + i + "/" + size);
                return ESP.EspLoaderErr.ESP_LOADER_ERROR_TIMEOUT.ordinal();
            }
        }
        return 1;
    }

    public int loaderPortSerialWrite(byte[] data, int size, int timeout) {
        if (connected) {
            try {
                usbSerialPort.write(data, timeout);
                return 0;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return 1;
    }

    public UsbSerialPort getSerial() {
        UsbSerialPort result = usbSerialPort;
        connected = false;
        usbSerialPort = null;
        return result;
    }

    @Override
    protected void onDestroy() {
        ESP.linkActivity = null;
        super.onDestroy();
    }
}