package com.pxs.terminal.ui.gallery;

import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.pxs.terminal.LinkActivity;
import com.pxs.terminal.R;
import com.pxs.terminal.SerialService;
import com.pxs.terminal.databinding.FragmentGalleryBinding;
import com.pxs.terminal.ui.home.HomeFragment;
import com.pxs.terminal.util.MyUtil;

import java.util.ArrayList;
import java.util.Locale;

public class GalleryFragment extends Fragment {

    private GalleryViewModel galleryViewModel;
    private FragmentGalleryBinding binding;
    private ListView listView;
    private ArrayAdapter<GalleryViewModel.ListItem> listAdapter;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        galleryViewModel =
                new ViewModelProvider(this).get(GalleryViewModel.class);

        binding = FragmentGalleryBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final TextView textView = binding.textGallery;
        galleryViewModel.getText().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(@Nullable String s) {
                textView.setText(s);
            }
        });
        listView = binding.listDevices;
        listAdapter = new ArrayAdapter<GalleryViewModel.ListItem>(getActivity(), 0, galleryViewModel.getListItems().getValue()) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                GalleryViewModel.ListItem item = galleryViewModel.getListItems().getValue().get(position);
                if (view == null)
                    view = getActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);
                if (item.driver == null)
                    text1.setText("<no driver>");
                else if (item.driver.getPorts().size() == 1)
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver", ""));
                else
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver", "") + ", Port " + item.port);
                text1.append(" " + SerialService.getInstance().getSerialInfo(item.device.getDeviceId()));
                text2.setText(String.format(Locale.US, "Vendor %04X, Product %04X", item.device.getVendorId(), item.device.getProductId()));
                if (galleryViewModel.getChoosePosition() == position) {
                    ((RadioButton) view.findViewById(R.id.radioButton)).setChecked(true);
                } else {
                    ((RadioButton) view.findViewById(R.id.radioButton)).setChecked(false);
                }
                return view;
            }
        };
        listView.setAdapter(listAdapter);
        galleryViewModel.getListItems().observe(getViewLifecycleOwner(), listItems -> {
            if (listItems.size() == 0) {
                textView.setVisibility(View.VISIBLE);
            } else {
                textView.setVisibility(View.INVISIBLE);
            }
            listAdapter.notifyDataSetChanged();
        });
        galleryViewModel.refresh(getActivity());
        listView.setOnItemClickListener((adapterView, view, i, l) -> {
            galleryViewModel.setChoosePosition(i);
            GalleryViewModel.ListItem item = galleryViewModel.getListItems().getValue().get(i);
            // ((LinkActivity)getActivity()).deviceId = item.device.getDeviceId();
            // ((LinkActivity)getActivity()).port = item.port;
            connect(i, item.device.getDeviceId(), item.port);
        });
        int nettype = MyUtil.getNetWorkState(getContext());
        switch (nettype) {
            case -1:
                binding.textIp.append("无网络连接\n本地IP:127.0.0.1");
                break;
            case 0:
                binding.textIp.append("移动网络连接\n本地IP:127.0.0.1\n移动IP:" + MyUtil.getNetWorkState(getContext()));
                break;
            case 1:
                binding.textIp.append("WIFI网络连接\n本地IP:127.0.0.1\n局域网IP:" + MyUtil.getLocalIPAddress(getContext()));
                break;
            default:
                break;
        }
        return root;
    }

    private void connect(final int index, int deviceId, int port) {
        ((LinkActivity) getActivity()).listener = new HomeFragment.OnDeivceLinkListener() {
            @Override
            public void connected() {
                SerialService.getInstance().startServer(((LinkActivity) getActivity()).getSerial(), 10010 + index);
                ((LinkActivity) getActivity()).runOnUiThread(() -> listAdapter.notifyDataSetChanged());
                ((LinkActivity) getActivity()).listener = null;
            }

            @Override
            public void disConnected() {

            }
        };
        if (!SerialService.getInstance().isSerialStart(deviceId)) {
            ((LinkActivity) getActivity()).connect(deviceId, port, 115200);
        } else {
            SerialService.getInstance().stopServer(deviceId);
            Toast.makeText(getContext(), "服务停止", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private Handler handler = new Handler();
    private Runnable deviceRunnable = null;

    @Override
    public void onResume() {
        super.onResume();
        if (deviceRunnable == null) {
            deviceRunnable = () -> {
                galleryViewModel.refresh(getActivity());
                handler.postDelayed(deviceRunnable, 500);
            };
        }
        handler.postDelayed(deviceRunnable, 500);
        SerialService.getInstance().setStateChanged(() -> getActivity().runOnUiThread(() -> listAdapter.notifyDataSetChanged()));
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(deviceRunnable);
        SerialService.getInstance().setStateChanged(null);
        super.onPause();
    }
}