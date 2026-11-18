package com.sunmi.cashdrawer.simulator;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CashDrawerHardwareSimulator.HardwareListener, SunmiWebhookServer.WebhookListener {

    private CashDrawerHardwareSimulator hardwareSimulator;
    private SunmiCloudPrinter printer;
    private SunmiWebhookServer webhookServer;

    private TextView tvStatusBadge;
    private LinearLayout layoutSlidingDrawer;
    private TextView tvAlertBanner;
    private TextView tvPin3Status;
    private TextView tvPin2Status;
    private TextView tvOpenTimer;
    private TextView tvOpenCount;
    private TextView tvCloudStatusPill;

    private ListView lvDrawerLogs;
    private LogAdapter logAdapter;
    private final List<DrawerEvent> logList = new ArrayList<>();

    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        initViews();
        initSimulatorAndServices();
        setupListeners();

        addLog(new DrawerEvent(
                DrawerEvent.Type.STATUS_QUERY,
                "System Initialized",
                "Sunmi CashDrawer Simulator & Webhook Server ready on port 8080.",
                "Default SN: " + SunmiCloudPrinter.DEFAULT_SN,
                false
        ));
    }

    private void initViews() {
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        layoutSlidingDrawer = findViewById(R.id.layoutSlidingDrawer);
        tvAlertBanner = findViewById(R.id.tvAlertBanner);
        tvPin3Status = findViewById(R.id.tvPin3Status);
        tvPin2Status = findViewById(R.id.tvPin2Status);
        tvOpenTimer = findViewById(R.id.tvOpenTimer);
        tvOpenCount = findViewById(R.id.tvOpenCount);
        tvCloudStatusPill = findViewById(R.id.tvCloudStatusPill);

        lvDrawerLogs = findViewById(R.id.lvDrawerLogs);
        logAdapter = new LogAdapter(this, logList);
        lvDrawerLogs.setAdapter(logAdapter);
        lvDrawerLogs.setOnItemClickListener((parent, view, position, id) -> {
            DrawerEvent event = logList.get(position);
            showEventDetailsDialog(event);
        });
    }

    private void initSimulatorAndServices() {
        hardwareSimulator = new CashDrawerHardwareSimulator();
        hardwareSimulator.setListener(this);

        printer = new SunmiCloudPrinter(384);

        webhookServer = new SunmiWebhookServer(8080);
        webhookServer.setListener(this);
        webhookServer.start();
    }

    private void setupListeners() {
        // Kick Drawer via Cloud
        findViewById(R.id.btnKickCloud).setOnClickListener(v -> kickCashDrawerViaCloud());

        // Manual Open
        findViewById(R.id.btnManualOpen).setOnClickListener(v -> {
            hardwareSimulator.manualOpen();
        });

        // Manual Close
        findViewById(R.id.btnManualClose).setOnClickListener(v -> {
            hardwareSimulator.manualClose();
        });

        // Simulate Alarm
        findViewById(R.id.btnSimAlarm).setOnClickListener(v -> {
            hardwareSimulator.forceTriggerAlarm();
        });

        // Simulate Webhook Callback from Sunmi Cloud
        findViewById(R.id.btnSimWebhook).setOnClickListener(v -> {
            String drawerStatus = hardwareSimulator.isOpen() ? "OPEN" : "CLOSED";
            webhookServer.simulateEvent(
                    "CASHDRAWER_STATUS_CHANGED",
                    SunmiCloudPrinter.DEFAULT_SN,
                    drawerStatus,
                    null
            );
            Toast.makeText(this, "Simulated Sunmi Cloud Webhook Dispatched", Toast.LENGTH_SHORT).show();
        });

        // Query Device Status via OpenAPI
        findViewById(R.id.btnCheckStatus).setOnClickListener(v -> queryOpenApiStatus());

        // Settings Dialog
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());

        // Clear Logs
        findViewById(R.id.btnClearLogs).setOnClickListener(v -> {
            logList.clear();
            logAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        });

        // Copy Logs
        findViewById(R.id.btnCopyLogs).setOnClickListener(v -> copyLogsToClipboard());
    }

    private void kickCashDrawerViaCloud() {
        String tradeNo = "CD_" + System.currentTimeMillis();
        String sn = SunmiCloudPrinter.DEFAULT_SN;

        // Build ESC/POS command: 1B 70 00 19 FA
        printer.clear();
        printer.openCashDrawer();
        String hexContent = printer.getOrderContent();

        addLog(new DrawerEvent(
                DrawerEvent.Type.CLOUD_KICK_SENT,
                "Cloud Kick Dispatched (ESC/POS 1B 70)",
                "Pushed cash drawer kick pulse to Cloud Printer SN: " + sn,
                "ESC/POS HEX: " + hexContent + " | TradeNo: " + tradeNo,
                false
        ));

        // Simulate hardware solenoid trigger
        hardwareSimulator.kickBySolenoid(0, 50, 500);

        // Issue HTTP call to OpenAPI (asynchronously)
        printer.pushOpenCashDrawerAsync(tradeNo, sn, new SunmiCloudPrinter.ApiCallback() {
            @Override
            public void onSuccess(String response) {
                addLog(new DrawerEvent(
                        DrawerEvent.Type.STATUS_QUERY,
                        "Cloud Push Response",
                        "Sunmi OpenAPI acknowledged cash drawer kick command.",
                        response,
                        false
                ));
            }

            @Override
            public void onError(Exception error) {
                addLog(new DrawerEvent(
                        DrawerEvent.Type.ERROR,
                        "Cloud Push Simulation",
                        "OpenAPI network note: " + error.getMessage(),
                        "Local hardware kick executed successfully.",
                        false
                ));
            }
        });
    }

    private void queryOpenApiStatus() {
        String sn = SunmiCloudPrinter.DEFAULT_SN;
        addLog(new DrawerEvent(
                DrawerEvent.Type.STATUS_QUERY,
                "Querying Device Status",
                "Calling OpenAPI /v2/printer/open/open/device/deviceStatus for SN: " + sn,
                "{\"sn\":\"" + sn + "\"}",
                false
        ));

        printer.deviceStatusAsync(sn, new SunmiCloudPrinter.ApiCallback() {
            @Override
            public void onSuccess(String response) {
                addLog(new DrawerEvent(
                        DrawerEvent.Type.STATUS_QUERY,
                        "Device Status Received",
                        "OpenAPI returned peripheral status data.",
                        response,
                        false
                ));
            }

            @Override
            public void onError(Exception error) {
                byte rawStatus = hardwareSimulator.getRealtimeStatusByte();
                String simJson = String.format(Locale.getDefault(),
                        "{\"code\":1,\"msg\":\"success (local simulation)\",\"data\":{\"sn\":\"%s\",\"cashdrawer\":{\"status\":%d,\"pin3_state\":\"%s\"}}}",
                        sn, hardwareSimulator.isOpen() ? 1 : 0, hardwareSimulator.isOpen() ? "LOW" : "HIGH");

                addLog(new DrawerEvent(
                        DrawerEvent.Type.STATUS_QUERY,
                        "Device Status (Local ESC/POS DLE EOT)",
                        "Real-time sensor byte: 0x" + String.format("%02X", rawStatus),
                        simJson,
                        false
                ));
            }
        });
    }

    // ==========================================
    // Hardware Simulator Callbacks
    // ==========================================

    @Override
    public void onStateChanged(boolean isOpen, String triggerSource, int openCount) {
        tvOpenCount.setText(String.valueOf(openCount));

        if (isOpen) {
            // Animate Drawer Sliding Out
            ObjectAnimator.ofFloat(layoutSlidingDrawer, "translationY", 0f, 30f)
                    .setDuration(250)
                    .start();

            tvStatusBadge.setText(R.string.status_open);
            tvStatusBadge.setBackgroundColor(Color.parseColor("#78350F"));
            tvStatusBadge.setTextColor(Color.parseColor("#FBBF24"));

            tvPin3Status.setText("LOW (Open)");
            tvPin3Status.setTextColor(Color.parseColor("#FBBF24"));

            addLog(new DrawerEvent(
                    DrawerEvent.Type.DRAWER_OPENED,
                    "Cash Drawer Opened",
                    "Source: " + triggerSource + " | RJ12 Pin 3 switched to LOW.",
                    "ESC/POS Status Byte: 0x04 (Bit 2 = 1)",
                    false
            ));

            if (vibrator != null) {
                try {
                    vibrator.vibrate(60);
                } catch (Exception ignored) {}
            }
        } else {
            // Animate Drawer Sliding In
            ObjectAnimator.ofFloat(layoutSlidingDrawer, "translationY", 30f, 0f)
                    .setDuration(200)
                    .start();

            tvStatusBadge.setText(R.string.status_closed);
            tvStatusBadge.setBackgroundColor(Color.parseColor("#064E3B"));
            tvStatusBadge.setTextColor(Color.parseColor("#34D399"));

            tvPin3Status.setText("HIGH (Closed)");
            tvPin3Status.setTextColor(Color.parseColor("#34D399"));

            tvOpenTimer.setText("00:00");

            addLog(new DrawerEvent(
                    DrawerEvent.Type.DRAWER_CLOSED,
                    "Cash Drawer Closed & Secured",
                    "Source: " + triggerSource + " | RJ12 Pin 3 restored to HIGH.",
                    "ESC/POS Status Byte: 0x00 (Bit 2 = 0)",
                    false
            ));
        }
    }

    @Override
    public void onTimerTick(long elapsedSeconds) {
        long minutes = elapsedSeconds / 60;
        long seconds = elapsedSeconds % 60;
        tvOpenTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
    }

    @Override
    public void onAlert(String alertType, String message) {
        tvAlertBanner.setVisibility(View.VISIBLE);
        tvAlertBanner.setText("⚠️ " + alertType + ": " + message);

        tvStatusBadge.setText(R.string.status_alert);
        tvStatusBadge.setBackgroundColor(Color.parseColor("#7F1D1D"));
        tvStatusBadge.setTextColor(Color.parseColor("#F87171"));

        addLog(new DrawerEvent(
                DrawerEvent.Type.ALERT_TIMEOUT,
                "⚠️ SECURITY ALERT: " + alertType,
                message,
                "Threshold: " + hardwareSimulator.getTimeoutThresholdSec() + "s exceeded. Triggered buzzer.",
                true
        ));

        if (vibrator != null) {
            try {
                vibrator.vibrate(new long[]{0, 200, 100, 200, 100, 200}, -1);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onAlertCleared() {
        tvAlertBanner.setVisibility(View.GONE);
    }

    @Override
    public void onSolenoidPulse(int pin, int onTimeMs, int offTimeMs) {
        tvPin2Status.setText(String.format(Locale.getDefault(), "24V PULSE (%dms)", onTimeMs));
        tvPin2Status.setTextColor(Color.parseColor("#F59E0B"));

        tvPin2Status.postDelayed(() -> {
            tvPin2Status.setText("IDLE");
            tvPin2Status.setTextColor(Color.parseColor("#94A3B8"));
        }, onTimeMs + 100);
    }

    // ==========================================
    // Webhook Server Callbacks
    // ==========================================

    @Override
    public void onWebhookReceived(String eventType, String sn, String rawJson, boolean signValid) {
        addLog(new DrawerEvent(
                DrawerEvent.Type.WEBHOOK_RECEIVED,
                "Sunmi Cloud Webhook Received",
                "Event: " + eventType + " | SN: " + sn + " | Signature: " + (signValid ? "VALID" : "UNVERIFIED"),
                rawJson,
                false
        ));
    }

    private void addLog(DrawerEvent event) {
        runOnUiThread(() -> {
            logList.add(event);
            logAdapter.notifyDataSetChanged();
            lvDrawerLogs.setSelection(logList.size() - 1);
        });
    }

    private void showEventDetailsDialog(DrawerEvent event) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(event.title);
        builder.setMessage(
                "Time: " + event.getFormattedTime() + "\n" +
                "Type: " + event.type.tag + "\n\n" +
                "Details:\n" + event.details + "\n\n" +
                (event.rawHexOrJson != null ? "Payload / Hex:\n" + event.rawHexOrJson : "")
        );
        builder.setPositiveButton("OK", null);
        builder.setNeutralButton("Copy Payload", (dialog, which) -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("SunmiLog", event.rawHexOrJson));
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Sunmi Cloud Configuration");

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        EditText etAppId = view.findViewById(R.id.etAppId);
        EditText etAppKey = view.findViewById(R.id.etAppKey);
        EditText etSn = view.findViewById(R.id.etSn);
        EditText etTimeout = view.findViewById(R.id.etTimeout);

        etAppId.setText(SunmiCloudPrinter.APP_ID);
        etAppKey.setText(SunmiCloudPrinter.APP_KEY);
        etSn.setText(SunmiCloudPrinter.DEFAULT_SN);
        etTimeout.setText(String.valueOf(hardwareSimulator.getTimeoutThresholdSec()));

        builder.setView(view);
        builder.setPositiveButton("Save", (dialog, which) -> {
            SunmiCloudPrinter.APP_ID = etAppId.getText().toString().trim();
            SunmiCloudPrinter.APP_KEY = etAppKey.getText().toString().trim();
            SunmiCloudPrinter.DEFAULT_SN = etSn.getText().toString().trim();
            try {
                int timeout = Integer.parseInt(etTimeout.getText().toString().trim());
                hardwareSimulator.setTimeoutThresholdSec(timeout);
            } catch (Exception ignored) {}
            Toast.makeText(this, "Settings updated", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void copyLogsToClipboard() {
        StringBuilder sb = new StringBuilder();
        for (DrawerEvent e : logList) {
            sb.append("[").append(e.getFormattedTime()).append("] ")
                    .append(e.type.tag).append(" ")
                    .append(e.title).append(" - ")
                    .append(e.details);
            if (e.rawHexOrJson != null && !e.rawHexOrJson.isEmpty()) {
                sb.append(" (").append(e.rawHexOrJson).append(")");
            }
            sb.append("\n");
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SunmiDrawerLogs", sb.toString()));
        Toast.makeText(this, "All logs copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webhookServer != null) {
            webhookServer.stop();
        }
    }
}
