package com.sunmi.cashdrawer.simulator;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Model representing a Sunmi Cloud Printer configured in the SunmiPrinter Setup utility.
 * Stores Model, Serial Number (SN), IP Address, Port, and Connection Type (Wi-Fi / Ethernet).
 */
public class SunmiPrinterDevice {

    public String model;
    public String sn;
    public String ip;
    public int port;
    public String connectionType;

    public SunmiPrinterDevice(String model, String sn, String ip, int port, String connectionType) {
        this.model = (model != null && !model.trim().isEmpty()) ? model.trim() : "NT311";
        this.sn = (sn != null && !sn.trim().isEmpty()) ? sn.trim() : "N402D05T00007";
        this.ip = (ip != null && !ip.trim().isEmpty()) ? ip.trim() : "192.168.103.134";
        this.port = (port > 0 && port <= 65535) ? port : 9100;
        this.connectionType = (connectionType != null && !connectionType.trim().isEmpty()) ? connectionType.trim() : "Wi-Fi / Ethernet";
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("model", model);
            obj.put("sn", sn);
            obj.put("ip", ip);
            obj.put("port", port);
            obj.put("connectionType", connectionType);
        } catch (JSONException ignored) {}
        return obj;
    }

    public static SunmiPrinterDevice fromJson(JSONObject obj) {
        if (obj == null) return null;
        String model = obj.optString("model", "NT311");
        String sn = obj.optString("sn", "N402D05T00007");
        String ip = obj.optString("ip", "192.168.103.134");
        int port = obj.optInt("port", 9100);
        String connectionType = obj.optString("connectionType", "Wi-Fi / Ethernet");
        return new SunmiPrinterDevice(model, sn, ip, port, connectionType);
    }

    @Override
    public String toString() {
        return model + " [" + sn + "] · " + ip + ":" + port + " (" + connectionType + ")";
    }
}
