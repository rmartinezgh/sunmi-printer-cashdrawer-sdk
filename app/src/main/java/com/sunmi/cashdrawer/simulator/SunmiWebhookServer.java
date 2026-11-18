package com.sunmi.cashdrawer.simulator;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Embedded Webhook HTTP Server
 * Receives Sunmi Cloud Printer callbacks:
 * - CASHDRAWER_STATUS_CHANGED (Open/Close events)
 * - CASHDRAWER_ALARM (Timeout unclosed alarms)
 * - ONLINE_STATUS_CHANGED
 */
public class SunmiWebhookServer {

    public interface WebhookListener {
        void onWebhookReceived(String eventType, String sn, String rawJson, boolean signValid);
    }

    private final int port;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private WebhookListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public SunmiWebhookServer(int port) {
        this.port = port;
    }

    public void setListener(WebhookListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                while (isRunning) {
                    try {
                        Socket socket = serverSocket.accept();
                        handleClient(socket);
                    } catch (Exception e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void handleClient(Socket socket) {
        new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 OutputStream out = socket.getOutputStream()) {

                String line = in.readLine();
                if (line == null) return;

                String[] parts = line.split(" ");
                String method = parts[0];
                String path = parts.length > 1 ? parts[1] : "/";

                Map<String, String> headers = new HashMap<>();
                int contentLength = 0;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    int idx = line.indexOf(":");
                    if (idx > 0) {
                        String key = line.substring(0, idx).trim().toLowerCase();
                        String val = line.substring(idx + 1).trim();
                        headers.put(key, val);
                        if ("content-length".equals(key)) {
                            contentLength = Integer.parseInt(val);
                        }
                    }
                }

                char[] bodyChars = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = in.read(bodyChars, read, contentLength - read);
                    if (r == -1) break;
                    read += r;
                }
                String body = new String(bodyChars);

                String sign = headers.get("sunmi-sign");
                String timestamp = headers.get("sunmi-timestamp");
                String nonce = headers.get("sunmi-nonce");
                boolean signValid = SunmiCloudPrinter.verifyCallbackSign(body, timestamp, nonce, sign);

                String eventType = "UNKNOWN";
                String sn = "";
                try {
                    JSONObject json = new JSONObject(body);
                    eventType = json.optString("event_type", "CASHDRAWER_EVENT");
                    sn = json.optString("sn", "UNKNOWN");
                } catch (Exception ignored) {}

                final String finalEventType = eventType;
                final String finalSn = sn;
                final String finalBody = body;
                final boolean finalSignValid = signValid;

                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onWebhookReceived(finalEventType, finalSn, finalBody, finalSignValid);
                    }
                });

                String responseBody = "{\"code\":1,\"msg\":\"success\"}";
                String httpResponse = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + responseBody.getBytes().length + "\r\n" +
                        "Connection: close\r\n\r\n" +
                        responseBody;
                out.write(httpResponse.getBytes());
                out.flush();

            } catch (Exception ignored) {
            }
        }).start();
    }

    /**
     * Simulates receiving an asynchronous Sunmi Cloud event callback
     */
    public void simulateEvent(String eventType, String sn, String drawerStatus, String alarmDesc) {
        long now = System.currentTimeMillis() / 1000;
        try {
            JSONObject root = new JSONObject();
            root.put("event_type", eventType);
            root.put("sn", sn);
            root.put("timestamp", now);

            JSONObject data = new JSONObject();
            if (drawerStatus != null) {
                data.put("drawer_status", drawerStatus);
                data.put("status_code", "OPEN".equalsIgnoreCase(drawerStatus) ? 1 : 0);
            }
            if (alarmDesc != null) {
                data.put("alarm_type", "TIMEOUT_UNCLOSED");
                data.put("alarm_code", 3001);
                data.put("alarm_desc", alarmDesc);
            }
            root.put("data", data);

            String body = root.toString();
            String timestamp = String.valueOf(now);
            String nonce = "123456";
            String sign = SunmiCloudPrinter.generateSign(body, timestamp, nonce);

            if (listener != null) {
                listener.onWebhookReceived(eventType, sn, body, true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
