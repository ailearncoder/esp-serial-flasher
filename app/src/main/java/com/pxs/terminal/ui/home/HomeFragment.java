package com.pxs.terminal.ui.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.pxs.terminal.JNI.ESP;
import com.pxs.terminal.LinkActivity;
import com.pxs.terminal.R;
import com.pxs.terminal.databinding.FragmentHomeBinding;
import com.pxs.terminal.ui.gallery.GalleryViewModel;
import com.pxs.terminal.util.CustomProber;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class HomeFragment extends Fragment {

    private final int ACTION_FLASH = 0;
    private final int ACTION_ERASE = 1;

    private HomeViewModel homeViewModel;
    private FragmentHomeBinding binding;
    private boolean linked = false;
    private int currentAction = ACTION_FLASH;

    class FileViewItem {
        public FileViewItem() {
            uri = null;
        }

        EditText editFile;
        EditText editAddress;
        Button buttonFile;
        Uri uri;
    }

    private ArrayList<FileViewItem> fileViewItems = new ArrayList<>();

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        final TextView textView = binding.textHome;
        homeViewModel =
                new ViewModelProvider(this).get(HomeViewModel.class);
        homeViewModel.getText().observe(getViewLifecycleOwner(), s -> textView.setText(s));
        String[] defaultTxt = new String[]{"bootloader_dio_40m.bin", "partitions.bin", "boot_app0.bin", "firmware.bin"};
        String[] defaultTxt2 = new String[]{"1000", "8000", "e000", "10000"};
        LinearLayout item = null;
        for (int i = 0; i < 8; i++) {
            FileViewItem viewItem = new FileViewItem();
            ViewGroup.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            item = (LinearLayout) LayoutInflater.from(getContext()).inflate(R.layout.item_bin, null);
            viewItem.editFile = item.findViewById(R.id.edit_file);
            viewItem.editFile.setId(i * 10);
            if (i < defaultTxt.length) {
                viewItem.editFile.setHint(defaultTxt[i]);
            }
            viewItem.editAddress = item.findViewById(R.id.edit_address);
            viewItem.editAddress.setId(i * 10 + 1);
            if (i < defaultTxt2.length) {
                viewItem.editAddress.setText(defaultTxt2[i]);
            }
            viewItem.buttonFile = item.findViewById(R.id.button_file);
            viewItem.buttonFile.setId(i * 10 + 2);
            viewItem.buttonFile.setOnClickListener(fileClick);
            binding.linear.addView(item, params);
            fileViewItems.add(viewItem);
        }
        View.OnClickListener listener = view -> {
            if (view == binding.buttonLink) {
                if (linked) {
                    new AlertDialog.Builder(getContext()).setTitle("提示").setMessage("设备已连接，是否断开连接？").setPositiveButton("断开", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ((LinkActivity) getActivity()).disconnect();
                        }
                    }).setNegativeButton("点错了", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            Toast.makeText(getContext(), "那我就不断开了哦！", Toast.LENGTH_SHORT).show();
                        }
                    }).create().show();
                } else {
                    showDeviceListDialog();
                }
            }
            if (view == binding.buttonFlash) {
                if (!checkFlashParams()) {
                    Toast.makeText(getContext(), "请保证至少有一组数据不为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!linked) {
                    // Toast.makeText(getContext(), "设备未连接", Toast.LENGTH_SHORT).show();
                    currentAction = ACTION_FLASH;
                    showDeviceListDialog();
                    return;
                }
                new FlashTask().execute();
            }
            if (view == binding.buttonErase) {
                new AlertDialog.Builder(getContext()).setTitle("注意").setMessage("此操作会擦除ESP Flash，是否确认？").setPositiveButton("确认", (dialogInterface, i) -> {
                    if (!linked) {
                        // Toast.makeText(getContext(), "设备未连接", Toast.LENGTH_SHORT).show();
                        currentAction = ACTION_ERASE;
                        showDeviceListDialog();
                        return;
                    }
                    new EraseTask().execute();
                }).setNegativeButton("点错了", null).create().show();
            }
        };
        binding.buttonLink.setOnClickListener(listener);
        binding.buttonFlash.setOnClickListener(listener);
        binding.buttonErase.setOnClickListener(listener);
        binding.textResult.setVisibility(View.GONE);
        binding.checkErase.setOnCheckedChangeListener((compoundButton, b) -> {
            if (b) {
                Toast.makeText(getContext(), "注意！烧录前会擦除芯片，请谨慎选择！！！", Toast.LENGTH_SHORT).show();
            }
        });
        return root;
    }

    private int selectIndex = 0;
    private View.OnClickListener fileClick = view -> {
        int pos = view.getId();
        int type = view.getId();
        pos /= 10;
        type %= 10;
        selectIndex = pos;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        // 任意类型文件
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 1);
    };

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1) {
            if (data == null) {
                // 用户未选择任何文件，直接返回
                return;
            }
            Uri uri = data.getData(); // 获取用户选择文件的URI
            try {
                InputStream inputStream = getActivity().getContentResolver().openInputStream(uri);
                DocumentFile documentFile = DocumentFile.fromSingleUri(getActivity(), uri);
                fileViewItems.get(selectIndex).editFile.setText(documentFile.getName() + " " + inputStream.available() / 1024 + "KB");
                fileViewItems.get(selectIndex).uri = uri;
                inputStream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private int chooseDeviceId = 0;
    private int choosePort = 0;

    private void showDeviceListDialog() {
        UsbManager usbManager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        ArrayList<String> items = new ArrayList<>();
        ArrayList<int[]> devs = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if (driver != null) {
                for (int port = 0; port < driver.getPorts().size(); port++) {
                    String item = "";
                    item += (driver.getClass().getSimpleName().replace("SerialDriver", "") + ", Port " + port);
                    item += (String.format(Locale.US, "\nVendor %04X, Product %04X", device.getVendorId(), device.getProductId()));
                    items.add(item);
                    int[] devData = new int[]{device.getDeviceId(), port};
                    devs.add(devData);
                }
            }
        }
        if (usbManager.getDeviceList().values().size() == 0) {
            Toast.makeText(getContext(), "没有发现USB设备，请检查是否正确连接！", Toast.LENGTH_SHORT).show();
            return;
        }
        if (items.size() == 0) {
            Toast.makeText(getContext(), "没有发现可支持的USB设备，请检查是否正确连接，或者更换其他设备尝试！", Toast.LENGTH_SHORT).show();
            return;
        }
        if (devs.size() == 1) {
            // 一个设备直接连接
            Toast.makeText(getContext(), "找到一个设备，开始连接...", Toast.LENGTH_SHORT).show();
            chooseDeviceId = devs.get(0)[0];
            choosePort = devs.get(0)[1];
            ((LinkActivity) getActivity()).deviceId = chooseDeviceId;
            ((LinkActivity) getActivity()).port = choosePort;
            ((LinkActivity) getActivity()).listener = listener;
            ((LinkActivity) getActivity()).connect();
            return;
        }
        AlertDialog.Builder listDialog =
                new AlertDialog.Builder(getContext());
        listDialog.setTitle("请选择设备");
        listDialog.setItems(items.toArray(new String[]{}), (dialog, which) -> {
            chooseDeviceId = devs.get(which)[0];
            choosePort = devs.get(which)[1];
            ((LinkActivity) getActivity()).deviceId = chooseDeviceId;
            ((LinkActivity) getActivity()).port = choosePort;
            ((LinkActivity) getActivity()).listener = listener;
            ((LinkActivity) getActivity()).connect();
        });
        listDialog.show();
    }

    OnDeivceLinkListener listener = new OnDeivceLinkListener() {
        @Override
        public void connected() {
            linked = true;
            binding.buttonLink.setText("断开连接");
            if (currentAction == ACTION_FLASH)
                new FlashTask().execute();
            if (currentAction == ACTION_ERASE)
                new EraseTask().execute();
            Toast.makeText(getContext(), "设备已连接", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void disConnected() {
            linked = false;
            binding.buttonLink.setText("连接设备");
            Toast.makeText(getContext(), "设备已断开连接", Toast.LENGTH_SHORT).show();
        }
    };

    public interface OnDeivceLinkListener {
        void connected();

        void disConnected();
    }

    private boolean checkFlashParams() {
        for (FileViewItem item : fileViewItems) {
            if (item.uri != null && !item.editAddress.getText().toString().equals("")) {
                return true;
            }
        }
        return false;
    }

    class FlashTask extends AsyncTask<Void, String, Integer> {
        private ProgressDialog dialog = new ProgressDialog(getActivity());
        private ESP.OnFlashListener flashListener = new ESP.OnFlashListener() {
            @Override
            public void OnFlashProcess(int percent) {
                publishProgress(null, percent + "");
            }

            @Override
            public void OnError(int errCode) {

            }
        };
        private boolean eraseFlash = false;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            dialog.setTitle("提示");
            dialog.setMessage("正在连接ESP，请稍后...");
            dialog.setCancelable(false);
            dialog.setMax(100);
            dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            dialog.show();
            binding.textResult.setVisibility(View.GONE);
            eraseFlash = binding.checkErase.isChecked();
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (values[0] != null)
                dialog.setMessage(values[0]);
            if (values[1] != null)
                dialog.setProgress(Integer.parseInt(values[1]));
        }

        class BinDataItem {
            public BinDataItem() {
                bin = null;
                address = 0;
            }

            byte[] bin;
            int address;
        }

        private BinDataItem getBinData(FileViewItem item) {
            BinDataItem bin = new BinDataItem();
            if (item.uri != null && !item.editAddress.getText().toString().equals("")) {
                try {
                    InputStream inputStream = getActivity().getContentResolver().openInputStream(item.uri);
                    int len = inputStream.available();
                    bin.bin = new byte[len];
                    int readl = inputStream.read(bin.bin);
                    if (readl != len) {
                        bin.bin = null;
                    }
                    bin.address = Integer.parseInt(item.editAddress.getText().toString(), 16);
                } catch (Exception e) {
                    bin.bin = null;
                    e.printStackTrace();
                }
            }
            return bin;
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            int ret = 0;
            String baudRate = PreferenceManager.getDefaultSharedPreferences(getContext()).getString("baud_rate", "115200");
            publishProgress("正在连接ESP 波特率：" + baudRate + "，请稍后...", null);
            ret = ESP.ConnectToTarget(Integer.parseInt(baudRate)); // 115200
            if (ret != 0) {
                return ret;
            }
            if (eraseFlash) {
                publishProgress("正在擦除ESP，请稍后...", null);
                ret = ESP.EraseFlash();
                if (ret != 0) {
                    return ret;
                }
            }
            for (FileViewItem item : fileViewItems) {
                BinDataItem bin = getBinData(item);
                if (bin.bin != null) {
                    publishProgress("正在烧录：" + item.editFile.getText().toString(), "0");
                    ret = ESP.FlashBinary(bin.bin, bin.address, flashListener);
                    if (ret != 0) {
                        break;
                    }
                }
            }
            if (ret == 0)
                ESP.ResetTarget();
            return ret;
        }

        @Override
        protected void onPostExecute(Integer integer) {
            super.onPostExecute(integer);
            dialog.cancel();
            if (integer != 0) {
                Toast.makeText(getContext(), "烧录失败，错误码：" + integer, Toast.LENGTH_SHORT).show();
                binding.textResult.setVisibility(View.VISIBLE);
                binding.textResult.setTextColor(Color.RED);
                binding.textResult.setText("烧录失败，错误码：" + integer);
            } else {
                Toast.makeText(getContext(), "恭喜，烧录成功！！", Toast.LENGTH_SHORT).show();
                binding.textResult.setVisibility(View.VISIBLE);
                binding.textResult.setTextColor(Color.GREEN);
                binding.textResult.setText("恭喜，烧录成功！！");
            }
            if (linked) {
                ((LinkActivity) getActivity()).disconnect();
            }
        }
    }


    class EraseTask extends AsyncTask<Void, String, Integer> {
        private ProgressDialog dialog = new ProgressDialog(getActivity());

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            dialog.setTitle("提示");
            dialog.setMessage("正在擦除ESP，请稍后...");
            dialog.setCancelable(false);
            dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            dialog.show();
            binding.textResult.setVisibility(View.GONE);
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (values[0] != null)
                dialog.setMessage(values[0]);
            if (values[1] != null)
                dialog.setProgress(Integer.parseInt(values[1]));
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            int ret = 0;
            String baudRate = PreferenceManager.getDefaultSharedPreferences(getContext()).getString("baud_rate", "115200");
            publishProgress("正在连接ESP 波特率：" + baudRate + "，请稍后...", null);
            ret = ESP.ConnectToTarget(Integer.parseInt(baudRate)); // 115200
            if (ret != 0) {
                publishProgress("连接ESP失败 波特率：" + baudRate , null);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return ret;
            }
            publishProgress("正在擦除ESP，请稍后...", null);
            ret = ESP.EraseFlash(); // 115200
            return ret;
        }

        @Override
        protected void onPostExecute(Integer integer) {
            super.onPostExecute(integer);
            dialog.cancel();
            if (integer != 0) {
                Toast.makeText(getContext(), "擦除失败，错误码：" + integer, Toast.LENGTH_SHORT).show();
                binding.textResult.setVisibility(View.VISIBLE);
                binding.textResult.setTextColor(Color.RED);
                binding.textResult.setText("擦除失败，错误码：" + integer);
            } else {
                Toast.makeText(getContext(), "恭喜，擦除成功！！", Toast.LENGTH_SHORT).show();
                binding.textResult.setVisibility(View.VISIBLE);
                binding.textResult.setTextColor(Color.GREEN);
                binding.textResult.setText("恭喜，擦除成功！！");
            }
            if (linked) {
                ((LinkActivity) getActivity()).disconnect();
            }
        }
    }

    @Override
    public void onDestroy() {
        if (linked) {
            ((LinkActivity) getActivity()).listener = null;
            ((LinkActivity) getActivity()).disconnect();
        }
        super.onDestroy();
    }
}