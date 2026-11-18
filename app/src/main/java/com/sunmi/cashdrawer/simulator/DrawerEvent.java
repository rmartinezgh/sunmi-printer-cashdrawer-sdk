package com.sunmi.cashdrawer.simulator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DrawerEvent {
    public enum Type {
        DRAWER_OPENED("[OPEN]", "#FBBF24"),
        DRAWER_CLOSED("[CLOSED]", "#34D399"),
        ALERT_TIMEOUT("[ALERT:TIMEOUT]", "#F87171"),
        ALERT_TAMPER("[ALERT:TAMPER]", "#EF4444"),
        CLOUD_KICK_SENT("[CLOUD:KICK]", "#60A5FA"),
        WEBHOOK_RECEIVED("[WEBHOOK:IN]", "#A78BFA"),
        STATUS_QUERY("[STATUS:API]", "#38BDF8"),
        ERROR("[ERROR]", "#F87171");

        public final String tag;
        public final String colorHex;

        Type(String tag, String colorHex) {
            this.tag = tag;
            this.colorHex = colorHex;
        }
    }

    public final long timestamp;
    public final Type type;
    public final String title;
    public final String details;
    public final String rawHexOrJson;
    public final boolean isAlert;

    public DrawerEvent(Type type, String title, String details, String rawHexOrJson, boolean isAlert) {
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.title = title;
        this.details = details;
        this.rawHexOrJson = rawHexOrJson;
        this.isAlert = isAlert;
    }

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}
