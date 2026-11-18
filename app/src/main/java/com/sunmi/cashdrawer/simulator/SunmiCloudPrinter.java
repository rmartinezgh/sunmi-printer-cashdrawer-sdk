package com.sunmi.cashdrawer.simulator;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Android-Optimized Sunmi Cloud Printer SDK
 * Includes Cash Drawer ESC/POS commands, OpenAPI endpoints, and async handlers.
 */
public class SunmiCloudPrinter {

    public static String APP_ID = "00000000000000000000000000000000";
    public static String APP_KEY = "00000000000000000000000000000000";
    public static String BASE_URL = "https://openapi.sunmi.com";
    public static String DEFAULT_SN = "N403000000000";

    public static final int ALIGN_LEFT   = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT  = 2;

    public static final int DRAWER_PIN_2 = 0; // Pin 2 (standard drawer)
    public static final int DRAWER_PIN_5 = 1; // Pin 5 (drawer 2)

    int DOTS_PER_LINE = 384;
    private final StringBuilder orderContent = new StringBuilder();
    private int charHSize = 1;
    private int asciiCharWidth = 12;
    private int cjkCharWidth = 24;

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ApiCallback {
        void onSuccess(String response);
        void onError(Exception error);
    }

    public SunmiCloudPrinter() {
    }

    public SunmiCloudPrinter(int dots_per_line) {
        this.DOTS_PER_LINE = dots_per_line;
    }

    public void clear() {
        orderContent.delete(0, orderContent.length());
    }

    public String getOrderContent() {
        return orderContent.toString();
    }

    public static String bytesToHexString(byte[] bytes) {
        StringBuilder hexstr = new StringBuilder();
        for (byte i: bytes)
            hexstr.append(String.format("%02x", i));
        return hexstr.toString();
    }

    public static String generateSign(String body, String timestamp, String nonce) throws Exception {
        String msg = body + APP_ID + timestamp + nonce;
        Mac hmacSha256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(APP_KEY.getBytes("UTF-8"), "HmacSHA256");
        hmacSha256.init(secretKey);
        byte[] result = hmacSha256.doFinal(msg.getBytes("UTF-8"));
        return bytesToHexString(result);
    }

    public static boolean verifyCallbackSign(String body, String timestamp, String nonce, String sign) {
        try {
            String expected = generateSign(body, timestamp, nonce);
            return expected.equalsIgnoreCase(sign);
        } catch (Exception e) {
            return false;
        }
    }

    public static String httpPost(String path, String body) {
        HttpURLConnection connection = null;
        InputStream is = null;
        OutputStream os = null;
        BufferedReader br = null;
        String result;

        Date date = new Date();
        Random random = new Random();
        String timestamp = String.format("%d", date.getTime() / 1000);
        String nonce = String.format("%06d", random.nextInt(1000000));

        try {
            URL url = new URL(BASE_URL + path);
            connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);

            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestProperty("Sunmi-Appid", APP_ID);
            connection.setRequestProperty("Sunmi-Timestamp", timestamp);
            connection.setRequestProperty("Sunmi-Nonce", nonce);
            connection.setRequestProperty("Sunmi-Sign", generateSign(body, timestamp, nonce));
            connection.setRequestProperty("Source", "openapi");
            connection.setRequestProperty("Content-Type", "application/json");

            os = connection.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();

            int code = connection.getResponseCode();
            is = (code >= 200 && code < 400) ? connection.getInputStream() : connection.getErrorStream();
            if (is != null) {
                br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sbf = new StringBuilder();
                String temp;
                while ((temp = br.readLine()) != null) {
                    sbf.append(temp);
                }
                result = sbf.toString();
            } else {
                result = "{\"code\":" + code + ",\"msg\":\"No response stream\"}";
            }
        } catch (Exception e) {
            result = "{\"code\":-1,\"msg\":\"" + e.getMessage() + "\"}";
        } finally {
            if (br != null) { try { br.close(); } catch (IOException ignored) {} }
            if (os != null) { try { os.close(); } catch (IOException ignored) {} }
            if (is != null) { try { is.close(); } catch (IOException ignored) {} }
            if (connection != null) { connection.disconnect(); }
        }
        return result;
    }

    public static void httpPostAsync(final String path, final String body, final ApiCallback callback) {
        executor.execute(() -> {
            try {
                final String res = httpPost(path, body);
                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess(res);
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    //////////////////////////////////////////////////
    // Cash Drawer ESC/POS Commands
    //////////////////////////////////////////////////

    /**
     * [ESC p m t1 t2] Generate cash drawer kick pulse
     */
    public void openCashDrawer(int pin, int onTimeMs, int offTimeMs) {
        int m = (pin == DRAWER_PIN_5) ? 1 : 0;
        int t1 = Math.min(Math.max(onTimeMs / 2, 1), 255);
        int t2 = Math.min(Math.max(offTimeMs / 2, 1), 255);
        orderContent.append(String.format("1b70%02x%02x%02x", m, t1, t2));
    }

    public void openCashDrawer() {
        openCashDrawer(DRAWER_PIN_2, 50, 500); // 1b700019fa
    }

    public void requestRealtimeCashDrawerStatus() {
        orderContent.append("100401"); // DLE EOT 1
    }

    public void enableAutoStatusBack(boolean enableDrawerStatus) {
        int n = enableDrawerStatus ? 0x01 : 0x00;
        orderContent.append(String.format("1d61%02x", n)); // GS a n
    }

    //////////////////////////////////////////////////
    // Cloud OpenAPI Calls
    //////////////////////////////////////////////////

    public void pushOpenCashDrawerAsync(String trade_no, String sn, ApiCallback callback) {
        clear();
        openCashDrawer();
        String body = String.format(
                "{\"trade_no\":\"%s\",\"sn\":\"%s\",\"order_type\":1,\"content\":\"%s\",\"count\":1,\"media_text\":\"开钱箱\",\"cycle\":1}",
                trade_no, sn, orderContent.toString());
        httpPostAsync("/v2/printer/open/open/device/pushContent", body, callback);
    }

    public void deviceStatusAsync(String sn, ApiCallback callback) {
        String body = String.format("{\"sn\":\"%s\"}", sn);
        httpPostAsync("/v2/printer/open/open/device/deviceStatus", body, callback);
    }

    public void onlineStatusAsync(String sn, ApiCallback callback) {
        String body = String.format("{\"sn\":\"%s\"}", sn);
        httpPostAsync("/v2/printer/open/open/device/onlineStatus", body, callback);
    }

    //////////////////////////////////////////////////
    // Text and Formatting ESC/POS Commands
    //////////////////////////////////////////////////

    public void appendText(String text) {
        try {
            byte[] bytes = text.getBytes("UTF-8");
            for (byte i: bytes)
                orderContent.append(String.format("%02x", i));
        } catch (UnsupportedEncodingException ignored) {
        }
    }

    public void lineFeed(int n) {
        for (int i = 0; i < n; i++)
            orderContent.append("0a");
    }

    public void restoreDefaultSettings() {
        charHSize = 1;
        orderContent.append("1b40");
    }

    public void setAlignment(int n) {
        if (n >= 0 && n <= 2)
            orderContent.append("1b61").append(String.format("%02x", n));
    }

    public void cutPaper(boolean full_cut) {
        orderContent.append("1d56").append(full_cut ? "30" : "31");
    }
}
