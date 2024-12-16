package com.pxs.terminal.ui.gallery;

import android.app.Activity;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.pxs.terminal.util.CustomProber;

import java.util.ArrayList;

public class GalleryViewModel extends ViewModel {

    private MutableLiveData<String> mText;
    private final MutableLiveData<ArrayList<ListItem>> listItems = new MutableLiveData<>();
    private int choosePosition = 0;

    static class ListItem {
        UsbDevice device;
        int port;
        UsbSerialDriver driver;

        ListItem(UsbDevice device, int port, UsbSerialDriver driver) {
            this.device = device;
            this.port = port;
            this.driver = driver;
        }
    }

    public GalleryViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("设备列表为空");
        listItems.setValue(new ArrayList<>());
    }

    void refresh(Context activity) {
        if (null == activity) {
            return;
        }
        UsbManager usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        listItems.getValue().clear();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if (driver != null) {
                for (int port = 0; port < driver.getPorts().size(); port++)
                    listItems.getValue().add(new ListItem(device, port, driver));
            } else {
                listItems.getValue().add(new ListItem(device, 0, null));
            }
        }
        listItems.postValue(listItems.getValue());
    }

    public LiveData<String> getText() {
        return mText;
    }

    public MutableLiveData<ArrayList<ListItem>> getListItems() {
        return listItems;
    }

    public int getChoosePosition() {
        return choosePosition;
    }

    public void setChoosePosition(int choosePosition) {
        this.choosePosition = choosePosition;
    }
}