package com.sunmi.cashdrawer.simulator;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.util.List;

public class LogAdapter extends BaseAdapter {

    private final Context context;
    private final List<DrawerEvent> events;

    public LogAdapter(Context context, List<DrawerEvent> events) {
        this.context = context;
        this.events = events;
    }

    @Override
    public int getCount() {
        return events.size();
    }

    @Override
    public DrawerEvent getItem(int position) {
        return events.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_drawer_log, parent, false);
            holder = new ViewHolder();
            holder.tvLogTag = convertView.findViewById(R.id.tvLogTag);
            holder.tvLogTitle = convertView.findViewById(R.id.tvLogTitle);
            holder.tvLogTime = convertView.findViewById(R.id.tvLogTime);
            holder.tvLogDetails = convertView.findViewById(R.id.tvLogDetails);
            holder.tvLogHex = convertView.findViewById(R.id.tvLogHex);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        DrawerEvent event = getItem(position);
        holder.tvLogTag.setText(event.type.tag);
        try {
            holder.tvLogTag.setTextColor(Color.parseColor(event.type.colorHex));
        } catch (Exception ignored) {
        }
        holder.tvLogTitle.setText(event.title);
        holder.tvLogTime.setText(event.getFormattedTime());
        holder.tvLogDetails.setText(event.details);

        if (event.rawHexOrJson != null && !event.rawHexOrJson.isEmpty()) {
            holder.tvLogHex.setVisibility(View.VISIBLE);
            holder.tvLogHex.setText(event.rawHexOrJson);
        } else {
            holder.tvLogHex.setVisibility(View.GONE);
        }

        return convertView;
    }

    static class ViewHolder {
        TextView tvLogTag;
        TextView tvLogTitle;
        TextView tvLogTime;
        TextView tvLogDetails;
        TextView tvLogHex;
    }
}
