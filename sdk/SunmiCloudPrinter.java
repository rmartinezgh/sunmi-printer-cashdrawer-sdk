package com.sunmi.cloudprinter.sdk;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.*;

/**
 * Sunmi Cloud Printer SDK with Cash Drawer Status Detection & Control
 * Reference: https://docs.sunmi.com/en-US/cdixeghjk491/xffzeghjk557
 */
public class SunmiCloudPrinter {

    // Replace with your applied APPID & APPKEY from partner.sunmi.com
    public static String APP_ID = "00000000000000000000000000000000";
    public static String APP_KEY = "00000000000000000000000000000000";
    public static String BASE_URL = "https://openapi.sunmi.com";

    public static final int ALIGN_LEFT   = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT  = 2;

    public static final int HRI_POS_ABOVE = 1;
    public static final int HRI_POS_BELOW = 2;

    public static final int DIFFUSE_DITHER   = 0;
    public static final int THRESHOLD_DITHER = 2;

    public static final int COLUMN_FLAG_BW_REVERSE = 1 << 0;
    public static final int COLUMN_FLAG_BOLD       = 1 << 1;
    public static final int COLUMN_FLAG_DOUBLE_H   = 1 << 2;
    public static final int COLUMN_FLAG_DOUBLE_W   = 1 << 3;

    // Cash Drawer States
    public static final int DRAWER_STATE_CLOSED = 0;
    public static final int DRAWER_STATE_OPEN   = 1;
    public static final int DRAWER_STATE_ERROR  = 2;

    // Standard RJ12 Pins
    public static final int DRAWER_PIN_2 = 0; // Pin 2 (Standard Drawer 1)
    public static final int DRAWER_PIN_5 = 1; // Pin 5 (Optional Drawer 2)

    int DOTS_PER_LINE = 384;

    StringBuilder orderContent = new StringBuilder();
    int charHSize = 1;
    int asciiCharWidth = 12;
    int cjkCharWidth = 24;

    ColumnSettings[] columnSettings = null;

    public static class ColumnSettings {
        public int width;
        public int alignment;
        public int flag;

        public ColumnSettings(ColumnSettings s) {
            this.width = s.width;
            this.alignment = s.alignment;
            this.flag = s.flag;
        }

        public ColumnSettings(int w, int a, int f) {
            this.width = w;
            this.alignment = a;
            this.flag = f;
        }
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
        String result = null;

        Date date = new Date();
        Random random = new Random();
        String timestamp = String.format("%d", date.getTime() / 1000);
        String nonce = String.format("%06d", random.nextInt(1000000));

        try {
            URL url = new URL(BASE_URL + path);
            connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);

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

    //////////////////////////////////////////////////
    // Cash Drawer ESC/POS Commands
    //////////////////////////////////////////////////

    /**
     * [ESC p m t1 t2] Generate cash drawer kick pulse
     * @param pin 0 for Pin 2 (standard drawer), 1 for Pin 5
     * @param onTimeMs pulse on time in milliseconds (10 - 500 ms)
     * @param offTimeMs pulse off time in milliseconds (50 - 500 ms)
     */
    public void openCashDrawer(int pin, int onTimeMs, int offTimeMs) {
        int m = (pin == DRAWER_PIN_5) ? 1 : 0;
        int t1 = Math.min(Math.max(onTimeMs / 2, 1), 255);
        int t2 = Math.min(Math.max(offTimeMs / 2, 1), 255);
        orderContent.append(String.format("1b70%02x%02x%02x", m, t1, t2));
    }

    /**
     * Open standard cash drawer with recommended pulse:
     * Pin 2, 50ms ON (0x19), 500ms OFF (0xfa) -> "1b700019fa"
     */
    public void openCashDrawer() {
        openCashDrawer(DRAWER_PIN_2, 50, 500);
    }

    /**
     * [DLE EOT n] Real-time status transmission request
     * n = 1: Transmit printer status (Bit 2 indicates cash drawer status)
     * Bit 2 = 0: Cash drawer closed
     * Bit 2 = 1: Cash drawer open
     */
    public void requestRealtimeCashDrawerStatus() {
        orderContent.append("100401");
    }

    /**
     * [GS a n] Enable Automatic Status Back (ASB)
     * When enabled, cash drawer status changes are pushed automatically.
     */
    public void enableAutoStatusBack(boolean enableDrawerStatus) {
        int n = enableDrawerStatus ? 0x01 : 0x00;
        orderContent.append(String.format("1d61%02x", n));
    }

    //////////////////////////////////////////////////
    // Sunmi Cloud OpenAPI Methods for Cash Drawer & Status
    //////////////////////////////////////////////////

    /**
     * Query detailed device status including cashdrawer switch state.
     */
    public String deviceStatus(String sn) {
        String body = String.format("{\"sn\":\"%s\"}", sn);
        return httpPost("/v2/printer/open/open/device/deviceStatus", body);
    }

    /**
     * Push a dedicated cash drawer open command to the cloud printer.
     */
    public String pushOpenCashDrawer(String trade_no, String sn) {
        clear();
        openCashDrawer();
        return pushContent(trade_no, sn, 1, 1, "开钱箱", 1);
    }

    /**
     * Parse Sunmi ESC/POS real-time status byte (DLE EOT 1)
     */
    public static class CashDrawerHardwareState {
        public final boolean isOpen;
        public final boolean isPaperOut;
        public final boolean isCoverOpen;
        public final boolean isError;
        public final int rawByte;

        public CashDrawerHardwareState(int statusByte) {
            this.rawByte = statusByte;
            // Bit 2: Cash drawer kick-out pin 3 status (1 = open, 0 = closed)
            this.isOpen = ((statusByte >> 2) & 0x01) == 1;
            // Bit 3: Error state
            this.isError = ((statusByte >> 3) & 0x01) == 1;
            // Bit 5: Cover open state
            this.isCoverOpen = ((statusByte >> 5) & 0x01) == 1;
            // Bit 6: Paper out state
            this.isPaperOut = ((statusByte >> 6) & 0x01) == 1;
        }

        @Override
        public String toString() {
            return String.format("DrawerState[isOpen=%s, coverOpen=%s, paperOut=%s, raw=0x%02X]",
                    isOpen, isCoverOpen, isPaperOut, rawByte);
        }
    }

    public String bindShop(String sn, int shop_id) {
        String body = String.format("{\"sn\":\"%s\",\"shop_id\":%d}", sn, shop_id);
        return httpPost("/v2/printer/open/open/device/bindShop", body);
    }

    public String unbindShop(String sn, int shop_id) {
        String body = String.format("{\"sn\":\"%s\",\"shop_id\":%d}", sn, shop_id);
        return httpPost("/v2/printer/open/open/device/unbindShop", body);
    }

    public String onlineStatus(String sn) {
        String body = String.format("{\"sn\":\"%s\"}", sn);
        return httpPost("/v2/printer/open/open/device/onlineStatus", body);
    }

    public String clearPrintJob(String sn) {
        String body = String.format("{\"sn\":\"%s\"}", sn);
        return httpPost("/v2/printer/open/open/device/clearPrintJob", body);
    }

    public String pushVoice(String sn, String content, int cycle, int interval, int expire_in) {
        String body = String.format(
            "{\"sn\":\"%s\",\"content\":\"%s\",\"cycle\":%d,\"interval\":%d,\"expire_in\":%d}",
            sn, content, cycle, interval, expire_in);
        return httpPost("/v2/printer/open/open/device/pushVoice", body);
    }

    public String pushContent(String trade_no, String sn, int count, int order_type, String media_text, int cycle) {
        String body = String.format(
            "{\"trade_no\":\"%s\",\"sn\":\"%s\",\"order_type\":%d,\"content\":\"%s\",\"count\":%d,\"media_text\":\"%s\",\"cycle\":%d}",
            trade_no, sn, order_type, orderContent.toString(), count, media_text, cycle);
        return httpPost("/v2/printer/open/open/device/pushContent", body);
    }

    public String printStatus(String trade_no) {
        String body = String.format("{\"trade_no\":\"%s\"}", trade_no);
        return httpPost("/v2/printer/open/open/ticket/printStatus", body);
    }

    public String newTicketNotify(String sn) {
        String body = String.format("{\"sn\":\"%s\"}", sn);
        return httpPost("/v2/printer/open/open/ticket/newTicketNotify", body);
    }

    //////////////////////////////////////////////////
    // Basic ESC/POS Commands
    //////////////////////////////////////////////////

    public void appendRawData(byte[] bytes) {
        for (byte i: bytes)
            orderContent.append(String.format("%02x", i));
    }

    public void appendUnicode(int unicode, int count) {
        if (count > 0) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < count; i++)
                text.append((char)unicode);
            appendText(text.toString());
        }
    }

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

    public void restoreDefaultLineSpacing() {
        orderContent.append("1b32");
    }

    public void setLineSpacing(int n) {
        if (n >= 0 && n <= 255)
            orderContent.append("1b33").append(String.format("%02x", n));
    }

    public void setPrintModes(boolean bold, boolean double_h, boolean double_w) {
        int n = 0;
        if (bold) n |= 8;
        if (double_h) n |= 16;
        if (double_w) n |= 32;
        charHSize = (double_w) ? 2 : 1;
        orderContent.append("1b21").append(String.format("%02x", n));
    }

    public void setCharacterSize(int h, int w) {
        int n = 0;
        if (h >= 1 && h <= 8) n |= (h - 1);
        if (w >= 1 && w <= 8) {
            n |= (w - 1) << 4;
            charHSize = w;
        }
        orderContent.append("1d21").append(String.format("%02x", n));
    }

    public void horizontalTab(int n) {
        for (int i = 0; i < n; i++)
            orderContent.append("09");
    }

    public void setAbsolutePrintPosition(int n) {
        if (n >= 0 && n <= 65535)
            orderContent.append("1b24").append(String.format("%02x%02x", (n & 0xff), ((n >> 8) & 0xff)));
    }

    public void setRelativePrintPosition(int n) {
        if (n >= -32768 && n <= 32767)
            orderContent.append("1b5c").append(String.format("%02x%02x", (n & 0xff), ((n >> 8) & 0xff)));
    }

    public void setAlignment(int n) {
        if (n >= 0 && n <= 2)
            orderContent.append("1b61").append(String.format("%02x", n));
    }

    public void setUnderlineMode(int n) {
        if (n >= 0 && n <= 2)
            orderContent.append("1b2d").append(String.format("%02x", n));
    }

    public void setBlackWhiteReverseMode(boolean enabled) {
        orderContent.append("1d42").append(enabled ? "01" : "00");
    }

    public void setUpsideDownMode(boolean enabled) {
        orderContent.append("1b7b").append(enabled ? "01" : "00");
    }

    public void cutPaper(boolean full_cut) {
        orderContent.append("1d56").append(full_cut ? "30" : "31");
    }

    public void postponedCutPaper(boolean full_cut, int n) {
        if (n >= 0 && n <= 255)
            orderContent.append("1d56").append(full_cut ? "61" : "62").append(String.format("%02x", n));
    }

    public void clearPaperNotTakenAlarm() {
        orderContent.append("1d2854010004");
    }

    public static void main(String[] args) {
        SunmiCloudPrinter printer = new SunmiCloudPrinter(384);

        System.out.println("=== Sunmi Cloud Printer Cash Drawer Demo ===");
        printer.restoreDefaultSettings();
        printer.setAlignment(ALIGN_CENTER);
        printer.appendText("*** 结账凭证 ***\n");
        printer.lineFeed(1);
        printer.appendText("实收现金: ¥100.00\n找零金额: ¥20.00\n");
        printer.lineFeed(2);

        // KICK CASHDRAWER COMMAND: ESC p 0 25 250 -> 1b700019fa
        printer.openCashDrawer();
        // Cut paper
        printer.cutPaper(false);

        System.out.println("Generated ESC/POS Hex payload with Cash Drawer kick:");
        System.out.println(printer.getOrderContent());

        // Check if cashdrawer kick command is present
        if (printer.getOrderContent().contains("1b700019fa")) {
            System.out.println("[SUCCESS] ESC/POS Cash Drawer kick command 1B 70 00 19 FA embedded!");
        }

        // Simulate Status Byte parsing
        // Real-time status byte where bit 2 is 1 (Drawer Open): e.g. 0x04 or 0x14
        CashDrawerHardwareState stateOpen = new CashDrawerHardwareState(0x04);
        System.out.println("Simulated Drawer Sensor Status (0x04): " + stateOpen);

        // Real-time status byte where bit 2 is 0 (Drawer Closed): e.g. 0x00
        CashDrawerHardwareState stateClosed = new CashDrawerHardwareState(0x00);
        System.out.println("Simulated Drawer Sensor Status (0x00): " + stateClosed);
    }
}
