package com.pxs.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class SerialService extends Service {
    private String notificationId = "串口服务";
    private String notificationName = "串口服务通知";
    private NotificationManager notificationManager;

    private static SerialService instance = null;
    private BroadcastReceiver broadcastReceiver;
    private static final String TAG = "SerialService";
    private PowerManager.WakeLock wakeLock;

    public static SerialService getInstance() {
        return instance;
    }

    public SerialService() {
        instance = this;
    }

    private Notification getNotification(String ticker, boolean main) {
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.mipmap.esp)
                .setContentTitle("串口服务")
                .setContentText(ticker)
                .setTicker(ticker);
        //设置Notification的ChannelID,否则不能正常显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(notificationId);
        }
        if (main) {
            {
                Intent intent1 = new Intent();
                intent1.setAction("SerialService");
                if (!wakeLock.isHeld()) {
                    intent1.putExtra("action", "lock");
                    PendingIntent intent = PendingIntent.getBroadcast(getApplicationContext(), 0x01, intent1, PendingIntent.FLAG_UPDATE_CURRENT);
                    builder.addAction(R.mipmap.esp, "锁定", intent); //图标，文字，点击事件
                } else {
                    intent1.putExtra("action", "unlock");
                    PendingIntent intent = PendingIntent.getBroadcast(getApplicationContext(), 0x01, intent1, PendingIntent.FLAG_UPDATE_CURRENT);
                    builder.addAction(R.mipmap.esp, "解锁", intent); //图标，文字，点击事件
                }
            }
            {
                Intent intent1 = new Intent();
                intent1.putExtra("action", "exit");
                intent1.setAction("SerialService");
                PendingIntent intent = PendingIntent.getBroadcast(getApplicationContext(), 0x02, intent1, PendingIntent.FLAG_UPDATE_CURRENT);
                builder.addAction(R.mipmap.esp, "退出", intent); //图标，文字，点击事件
            }
        }
        Notification notification = builder.build();
        return notification;
    }

    private Notification getNotification(String ticker) {
        return getNotification(ticker, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.ON_AFTER_RELEASE | PowerManager.PARTIAL_WAKE_LOCK, "SerialService:Tag");
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "onReceive: " + intent.getAction());
                if (intent.getAction().equals("SerialService")) {
                    String action = intent.getStringExtra("action");
                    if ("exit".equals(action)) {
                        try {
                            Os.kill(Os.getpid(), 9);
                        } catch (ErrnoException e) {
                            e.printStackTrace();
                        }
                    }
                    if ("lock".equals(action)) {
                        if (!wakeLock.isHeld())
                            wakeLock.acquire();
                        notificationManager.notify(1, getNotification("运行中...", true));
                    }
                    if ("unlock".equals(action)) {
                        if (wakeLock.isHeld())
                            wakeLock.release();
                        notificationManager.notify(1, getNotification("运行中...", true));
                    }
                }
            }
        };
        registerReceiver(broadcastReceiver, new IntentFilter("SerialService"));
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(broadcastReceiver);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        //创建NotificationChannel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(notificationId, notificationName, NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel(channel);
        }
        startForeground(1, getNotification("运行中...", true));
        return START_STICKY;
    }

    private HashMap<Integer, UsbSerialThread> serialsId = new HashMap<>();
    private HashMap<Integer, ListenThread> listenPorts = new HashMap<>();
    private ArrayList<LinkThread> links = new ArrayList<>();

    public String getSerialInfo(int deviceId) {
        String result = "";
        for (Integer key : serialsId.keySet()) {
            if (deviceId == key) {
                int port = serialsId.get(key).bindPort;
                boolean isListen = false;
                for (ListenThread item : listenPorts.values()) {
                    if (item.port == port) {
                        isListen = true;
                    }
                }
                result += "监听端口:" + port;
                int linkNum = 0;
                for (LinkThread item : links) {
                    if (item.listenPort == port) {
                        linkNum++;
                    }
                }
                result += " 连接数:" + linkNum;
            }
        }
        if (result.equals("")) {
            result = "服务未开启";
        }
        return result;
    }

    public boolean isSerialStart(int deviceId) {
        return serialsId.containsKey(deviceId);
    }

    public void stopServer(int deviceId) {
        if (serialsId.containsKey(deviceId)) {
            serialsId.get(deviceId).running = false;
        }
    }

    public synchronized void startServer(final UsbSerialPort usbSerialPort, final int port) {
        if (!serialsId.containsKey(usbSerialPort.getDevice().getDeviceId())) {
            UsbSerialThread usbSerialThread = new UsbSerialThread(usbSerialPort, port);
            serialsId.put(usbSerialPort.getDevice().getDeviceId(), usbSerialThread);
            usbSerialThread.start();
        }
        if (!listenPorts.containsKey(port)) {
            ListenThread listenThread = new ListenThread(port);
            listenPorts.put(port, listenThread);
            listenThread.start();
        }
    }

    class UsbSerialThread extends Thread {
        UsbSerialPort usbSerialPort;
        boolean running = false;
        int bindPort = 0;

        public UsbSerialThread(UsbSerialPort usbSerialPort, int bindPort) {
            this.usbSerialPort = usbSerialPort;
            this.bindPort = bindPort;
        }

        @Override
        public void run() {
            setName("UsbSerialThread: " + usbSerialPort.getSerial() + ":" + bindPort);
            running = true;
            byte[] rec = new byte[128];
            int num;
            while (running) {
                try {
                    num = usbSerialPort.read(rec, 200);
                    if (num <= 0)
                        continue;
                    for (LinkThread item : links) {
                        if (item.outputStream != null && item.listenPort == bindPort) {
                            try {
                                item.outputStream.write(rec, 0, num);
                            } catch (Exception e) {
                                item.running = false;
                                e.printStackTrace();
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    running = false;
                }
            }
            try {
                usbSerialPort.close();
            } catch (Exception e) {

            }
            if (serialsId.containsKey(usbSerialPort.getDevice().getDeviceId())) {
                serialsId.remove(usbSerialPort.getDevice().getDeviceId());
                if (stateChanged != null)
                    stateChanged.run();
            }
            // 判断当前端口是否还有其他串口使用，没有则关闭监听
            boolean shouldClose = true;
            for (UsbSerialThread item : serialsId.values()) {
                if (item.bindPort == bindPort) {
                    shouldClose = false;
                    break;
                }
            }
            if (shouldClose) {
                // 关闭监听
                for (ListenThread item : listenPorts.values()) {
                    if (item.port == bindPort) {
                        item.stopListen();
                    }
                }
                // 关闭链接
                for (LinkThread item : links) {
                    if (item.listenPort == bindPort) {
                        item.dislink();
                    }
                }
            }
        }
    }

    // private int randId = 1;

    private void notifyText(String text) {
        if (notificationManager != null)
            notificationManager.notify(2, getNotification(text));
    }

    class ListenThread extends Thread {
        boolean running = false;
        int port = 10010;
        ServerSocket serverSocket;

        public ListenThread(int port) {
            this.port = port;
        }

        public void stopListen() {
            if (running) {
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        @Override
        public void run() {
            setName("ListenThread: port:" + port);
            running = true;
            try {
                notifyText("开始监听:" + port);
                serverSocket = new ServerSocket(port);
                while (running) {
                    Socket socket = serverSocket.accept();
                    notifyText("连接到：" + socket.getRemoteSocketAddress().toString());
                    LinkThread linkThread = new LinkThread(socket);
                    linkThread.start();
                    links.add(linkThread);
                    if (stateChanged != null)
                        stateChanged.run();
                }
            } catch (IOException e) {
                e.printStackTrace();
                running = false;
            }
            notifyText("监听结束:" + port);
            listenPorts.remove(port);
            if (stateChanged != null)
                stateChanged.run();
        }
    }

    private synchronized void serialWrite(UsbSerialPort usbSerialPort, byte[] data) throws IOException {
        usbSerialPort.write(data, 0);
    }

    class LinkThread extends Thread {
        Socket socket;
        boolean running = false;
        OutputStream outputStream = null;
        int listenPort = 0;
        private boolean espFlashMode = false;
        private boolean esp32r0_delay = false;

        public LinkThread(Socket socket) {
            this.socket = socket;
        }

        private boolean loaderPortResetTarget(UsbSerialPort usbSerialPort) {
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
                return false;
            }
            return true;
        }

        private boolean loaderPortEnterBootloader(UsbSerialPort usbSerialPort) {
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
                return false;
            }
            return true;
        }

        public void dislink() {
            if (running) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private boolean isSyncCmd(byte[] data) {
            // sync cmd
            // -64 00 08 36 00 00 00 00 00 7 7 18 32 85*32 -64
            if (data[1] != 0)
                return false;
            if (data[2] != 8)
                return false;
            if (data[3] != 36)
                return false;
            if (data[4] != 0)
                return false;
            if (data[9] != 7)
                return false;
            if (data[10] != 7)
                return false;
            if (data[11] != 18)
                return false;
            if (data[12] != 32)
                return false;
            for (int i = 0; i < 32; i++) {
                if (data[i + 13] != 85)
                    return false;
            }
            return true;
        }

        @Override
        public void run() {
            try {
                running = true;
                InputStream inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                listenPort = socket.getLocalPort();
                setName(String.format("LinkThread: %s->%s", socket.getLocalAddress().toString(), socket.getRemoteSocketAddress().toString()));
                byte[] rec = new byte[128];
                int num;
                while (running) {
                    num = inputStream.read(rec);
                    // esp_flash_mode
                    if (!espFlashMode && num == 46 && rec[0] == -64 && rec[45] == -64) {
                        // sync cmd
                        // -64 00 08 36 00 00 00 00 00 7 7 18 32 85*32 -64
                        if (isSyncCmd(rec)) {
                            Log.i(TAG, "run: esp_flash_mode");
                            espFlashMode = true;
                            for (UsbSerialThread item : serialsId.values()) {
                                if (item.bindPort == listenPort) {
                                    if (loaderPortEnterBootloader(item.usbSerialPort)) {
                                        notifyText(listenPort + "端口 进入升级模式OK");
                                    } else {
                                        notifyText(listenPort + "端口 进入升级模式失败");
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    if (num < 0) {
                        running = false;
                        break;
                    }
                    byte[] w = new byte[num];
                    System.arraycopy(rec, 0, w, 0, num);
                    for (UsbSerialThread item : serialsId.values()) {
                        try {
                            if (this.listenPort == item.bindPort)
                                serialWrite(item.usbSerialPort, w);
                        } catch (Exception e) {
                            e.printStackTrace();
                            item.running = false;
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                socket.close();
            } catch (IOException e) {
            }
            links.remove(this);
            if (stateChanged != null)
                stateChanged.run();
            notifyText("断开连接：" + socket.getRemoteSocketAddress().toString());
            if (espFlashMode) {
                for (UsbSerialThread item : serialsId.values()) {
                    if (item.bindPort == listenPort) {
                        if (loaderPortResetTarget(item.usbSerialPort)) {
                            notifyText(listenPort + "端口 复位OK");
                        } else {
                            notifyText(listenPort + "端口 复位失败");
                        }
                        break;
                    }
                }
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        return null;
    }

    private Runnable stateChanged;

    public void setStateChanged(Runnable stateChanged) {
        this.stateChanged = stateChanged;
    }
}