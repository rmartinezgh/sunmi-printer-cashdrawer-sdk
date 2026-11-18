package com.sunmi.cashdrawer.simulator;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import java.util.List;

public class SavedPrinterAdapter extends ArrayAdapter<SunmiPrinterDevice> {

    public interface OnPrinterActionListener {
        void onConnectClicked(SunmiPrinterDevice device);
        void onDeleteClicked(SunmiPrinterDevice device, int position);
    }

    private final Context context;
    private final List<SunmiPrinterDevice> devices;
    private final OnPrinterActionListener listener;

    public SavedPrinterAdapter(Context context, List<SunmiPrinterDevice> devices, OnPrinterActionListener listener) {
        super(context, 0, devices);
        this.context = context;
        this.devices = devices;
        this.listener = listener;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_saved_printer, parent, false);
        }

        final SunmiPrinterDevice device = devices.get(position);

        TextView tvModel = convertView.findViewById(R.id.tvItemModel);
        TextView tvIp = convertView.findViewById(R.id.tvItemIp);
        TextView tvSnAndType = convertView.findViewById(R.id.tvItemSnAndType);
        Button btnConnect = convertView.findViewById(R.id.btnItemConnect);
        Button btnDelete = convertView.findViewById(R.id.btnItemDelete);

        tvModel.setText(device.model);
        tvIp.setText(device.ip + ":" + device.port);
        tvSnAndType.setText("SN: " + device.sn + " · " + device.connectionType);

        View.OnClickListener selectListener = v -> {
            if (listener != null) {
                listener.onConnectClicked(device);
            }
        };

        convertView.setOnClickListener(selectListener);
        btnConnect.setOnClickListener(selectListener);

        btnDelete.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClicked(device, position);
            }
        });

        return convertView;
    }
}
