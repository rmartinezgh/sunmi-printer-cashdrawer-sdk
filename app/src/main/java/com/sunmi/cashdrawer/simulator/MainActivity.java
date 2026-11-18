package com.sunmi.cashdrawer.simulator;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CashDrawerHardwareSimulator.HardwareListener, SunmiWebhookServer.WebhookListener, SunmiBluetoothManager.BluetoothCallback {

    private CashDrawerHardwareSimulator hardwareSimulator;
    private SunmiCloudPrinter printer;
    private SunmiWebhookServer webhookServer;
    private SunmiBluetoothManager bluetoothManager;

    private TextView tvStatusBadge;
    private LinearLayout layoutSlidingDrawer;
    private TextView tvAlertBanner;
    private TextView tvPin3Status;
    private TextView tvPin2Status;
    private TextView tvOpenTimer;
    private TextView tvOpenCount;
    private TextView tvBtStatusPill;
    private TextView tvDeviceHeaderLabel;
    private TextView tvSignalHealth;
    private TextView tvSignalLatency;
    private Button btnConnectBt;
    private Button btnConnectLan;
    private Button btnTogglePolarity;
    private Button btnAutoCalibrate;

    private SunmiLanScanner lanScanner;

    private ListView lvDrawerLogs;
    private LogAdapter logAdapter;
    private final List<DrawerEvent> logList = new ArrayList<>();

    private Vibrator vibrator;

    private static final int REQ_BT_PERMS = 101;

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
                "cPad System Ready",
                "Sunmi cPad & NT311 Controller Initialized. Bluetooth & Cloud ready.",
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
        tvBtStatusPill = findViewById(R.id.tvBtStatusPill);
        tvDeviceHeaderLabel = findViewById(R.id.tvDeviceHeaderLabel);
        tvSignalHealth = findViewById(R.id.tvSignalHealth);
        tvSignalLatency = findViewById(R.id.tvSignalLatency);
        btnConnectBt = findViewById(R.id.btnConnectBt);
        btnConnectLan = findViewById(R.id.btnConnectLan);
        btnTogglePolarity = findViewById(R.id.btnTogglePolarity);
        btnAutoCalibrate = findViewById(R.id.btnAutoCalibrate);

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

        bluetoothManager = new SunmiBluetoothManager();
        bluetoothManager.setCallback(this);
        updatePolarityButtonUI();
    }

    private void setupListeners() {
        // Bluetooth Connect Button
        btnConnectBt.setOnClickListener(v -> handleBluetoothConnectAction());

        // Wi-Fi / Ethernet LAN Connect Button
        btnConnectLan.setOnClickListener(v -> handleLanConnectAction());

        // Polarity Toggle
        btnTogglePolarity.setOnClickListener(v -> togglePolarity());

        // Polarity Auto-Calibrate (detects microswitch edge on kick)
        if (btnAutoCalibrate != null) {
            btnAutoCalibrate.setOnClickListener(v -> handleAutoCalibrateAction());
        }

        // Kick Drawer via Bluetooth (NT311 ESC/POS)
        findViewById(R.id.btnKickBt).setOnClickListener(v -> kickCashDrawerViaBluetooth());

        // Poll NT311 Real-Time Status over Bluetooth
        findViewById(R.id.btnQueryBtStatus).setOnClickListener(v -> queryBluetoothStatus());

        // Test Print & Kick over Bluetooth
        findViewById(R.id.btnPrintReceiptBt).setOnClickListener(v -> printReceiptAndKickBluetooth());

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

        // Settings Dialog
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());

        // Cash Drawer Logs Report Dialog (from top header or audit section)
        findViewById(R.id.btnShowReport).setOnClickListener(v -> showCashDrawerReportDialog());
        findViewById(R.id.btnLogsReport).setOnClickListener(v -> showCashDrawerReportDialog());

        // Clear Logs
        findViewById(R.id.btnClearLogs).setOnClickListener(v -> {
            logList.clear();
            logAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        });

        // Copy Logs
        findViewById(R.id.btnCopyLogs).setOnClickListener(v -> copyLogsToClipboard());
    }

    // ==========================================
    // Bluetooth Connection & Actions (cPad -> NT311)
    // ==========================================

    private void togglePolarity() {
        boolean newPolarity = !bluetoothManager.isInvertPolarity();
        bluetoothManager.setInvertPolarity(newPolarity);
        updatePolarityButtonUI();

        String polarityName = newPolarity ? "INVERTED (0=Open, 1=Closed / N.C.)" : "NORMAL (1=Open, 0=Closed / N.O.)";
        addLog(new DrawerEvent(
                DrawerEvent.Type.STATUS_QUERY,
                "Microswitch Polarity Changed",
                "Switched to " + polarityName,
                "Matches standard POS microswitch configuration",
                false
        ));
        Toast.makeText(this, "Polarity set to: " + (newPolarity ? "Inverted (N.C.)" : "Normal (N.O.)"), Toast.LENGTH_SHORT).show();

        if (bluetoothManager.isConnected()) {
            bluetoothManager.queryStatusRealtime();
        }
    }

    private void updatePolarityButtonUI() {
        if (btnTogglePolarity == null) return;
        boolean isInverted = bluetoothManager.isInvertPolarity();
        if (isInverted) {
            btnTogglePolarity.setText("🔄 Polarity: INVERTED (N.C.)");
            btnTogglePolarity.setTextColor(Color.parseColor("#34D399"));
            btnTogglePolarity.setBackgroundColor(Color.parseColor("#064E3B"));
        } else {
            btnTogglePolarity.setText("🔄 Polarity: NORMAL (N.O.)");
            btnTogglePolarity.setTextColor(Color.parseColor("#93C5FD"));
            btnTogglePolarity.setBackgroundColor(Color.parseColor("#1E3A8A"));
        }
    }

    private void handleAutoCalibrateAction() {
        if (bluetoothManager.isConnected()) {
            Toast.makeText(this, "⚡ Firing test pulse... calibrating microswitch polarity", Toast.LENGTH_SHORT).show();
            bluetoothManager.startAutoCalibration();
            hardwareSimulator.kickBySolenoid(0, 50, 500);
            addLog(new DrawerEvent(
                    DrawerEvent.Type.STATUS_QUERY,
                    "Auto-Polarity Calibration Initiated",
                    "Dispatched 400ms pulse to Pin 2. High-speed burst tracking active (80ms).",
                    "Analyzing microswitch voltage edge transition...",
                    false
            ));
        } else {
            // Simulator demonstration mode
            boolean newInvert = !bluetoothManager.isInvertPolarity();
            bluetoothManager.setInvertPolarity(newInvert);
            updatePolarityButtonUI();
            hardwareSimulator.kickBySolenoid(0, 50, 500);
            Toast.makeText(this, "Simulation: Calibrated to " + (newInvert ? "INVERTED (N.C.)" : "NORMAL (N.O.)"), Toast.LENGTH_SHORT).show();
            addLog(new DrawerEvent(
                    DrawerEvent.Type.STATUS_QUERY,
                    "Simulated Polarity Calibration",
                    "Auto-calibrated polarity to: " + (newInvert ? "INVERTED (N.C.)" : "NORMAL (N.O.)"),
                    "Connect NT311 for live hardware microswitch calibration",
                    false
            ));
        }
    }

    private void handleBluetoothConnectAction() {
        if (bluetoothManager.isConnected()) {
            bluetoothManager.disconnect();
            return;
        }

        // Check Android 13 Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, REQ_BT_PERMS);
                return;
            }
        }

        if (!bluetoothManager.isBluetoothAvailable()) {
            Toast.makeText(this, "Bluetooth not available on this device", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothManager.isBluetoothEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth in settings first", Toast.LENGTH_SHORT).show();
            return;
        }

        showPairedDevicesDialog();
    }

    @SuppressLint("MissingPermission")
    private void showPairedDevicesDialog() {
        final List<BluetoothDevice> devices = bluetoothManager.getPairedDevices();
        if (devices.isEmpty()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("No Paired Sunmi Printers");
            builder.setMessage("No paired Bluetooth devices found.\n\nPlease open Android Settings -> Bluetooth, pair with your Sunmi NT311 printer, and try again.");
            builder.setPositiveButton("OK", null);
            builder.show();
            return;
        }

        String[] deviceNames = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice dev = devices.get(i);
            String name = dev.getName() != null ? dev.getName() : "Unknown Device";
            deviceNames[i] = name + " (" + dev.getAddress() + ")";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Sunmi NT311 Bluetooth Printer");
        builder.setItems(deviceNames, (dialog, which) -> {
            BluetoothDevice selected = devices.get(which);
            addLog(new DrawerEvent(
                    DrawerEvent.Type.STATUS_QUERY,
                    "Connecting Bluetooth",
                    "Initiating RFCOMM SPP connection to " + selected.getName(),
                    selected.getAddress(),
                    false
            ));
            bluetoothManager.connect(selected);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void handleLanConnectAction() {
        if (bluetoothManager.isConnected() && bluetoothManager.getConnectionType() == SunmiBluetoothManager.ConnectionType.NETWORK) {
            bluetoothManager.disconnect();
            return;
        }
        showLanConnectDialog();
    }

    private void showLanConnectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_connect_lan, null);
        builder.setView(view);

        final AlertDialog dialog = builder.create();

        final EditText etPrinterIp = view.findViewById(R.id.etPrinterIp);
        final EditText etPrinterPort = view.findViewById(R.id.etPrinterPort);
        final Button btnScanLan = view.findViewById(R.id.btnScanLan);
        final ProgressBar pbScanProgress = view.findViewById(R.id.pbScanProgress);
        final TextView tvScanStatus = view.findViewById(R.id.tvScanStatus);
        final ListView lvDiscovered = view.findViewById(R.id.lvDiscoveredPrinters);

        final List<String> discoveredPrinters = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredPrinters);
        lvDiscovered.setAdapter(adapter);

        lvDiscovered.setOnItemClickListener((parent, v, position, id) -> {
            String selected = discoveredPrinters.get(position);
            if (selected.contains(":")) {
                String ip = selected.substring(0, selected.indexOf(':')).trim();
                etPrinterIp.setText(ip);
            }
        });

        btnScanLan.setOnClickListener(v -> {
            discoveredPrinters.clear();
            adapter.notifyDataSetChanged();
            pbScanProgress.setVisibility(View.VISIBLE);
            tvScanStatus.setText("Scanning subnet for port 9100...");
            btnScanLan.setEnabled(false);

            if (lanScanner == null) {
                lanScanner = new SunmiLanScanner();
            }

            lanScanner.startScan(new SunmiLanScanner.ScanCallback() {
                @Override
                public void onPrinterDiscovered(String ip, int port, String modelDescription) {
                    discoveredPrinters.add(ip + ":" + port + " (" + modelDescription + ")");
                    adapter.notifyDataSetChanged();
                }

                @Override
                public void onScanProgress(int progress, int total, String currentIp) {
                    tvScanStatus.setText(progress + "/" + total + " (" + currentIp + ")");
                }

                @Override
                public void onScanCompleted(List<String> discoveredIps) {
                    pbScanProgress.setVisibility(View.GONE);
                    btnScanLan.setEnabled(true);
                    if (discoveredIps.isEmpty()) {
                        tvScanStatus.setText("Scan done. No open port 9100 found. Enter IP manually.");
                    } else {
                        tvScanStatus.setText("Found " + discoveredIps.size() + " network printer(s)!");
                        etPrinterIp.setText(discoveredIps.get(0));
                    }
                }
            });
        });

        view.findViewById(R.id.btnConnectLanSubmit).setOnClickListener(v -> {
            String ip = etPrinterIp.getText().toString().trim();
            int port = 9100;
            try {
                port = Integer.parseInt(etPrinterPort.getText().toString().trim());
            } catch (Exception ignored) {}

            if (ip.isEmpty()) {
                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show();
                return;
            }

            if (lanScanner != null && lanScanner.isScanning()) {
                lanScanner.cancelScan();
            }

            dialog.dismiss();

            addLog(new DrawerEvent(
                    DrawerEvent.Type.STATUS_QUERY,
                    "Connecting Wi-Fi / Ethernet LAN",
                    "Opening TCP RAW socket to " + ip + ":" + port,
                    "Target: " + ip + ":" + port,
                    false
            ));
            Toast.makeText(this, "Connecting to " + ip + ":" + port + "...", Toast.LENGTH_SHORT).show();
            bluetoothManager.connectNetwork(ip, port);
        });

        view.findViewById(R.id.btnCancelLan).setOnClickListener(v -> {
            if (lanScanner != null && lanScanner.isScanning()) {
                lanScanner.cancelScan();
            }
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> {
            if (lanScanner != null && lanScanner.isScanning()) {
                lanScanner.cancelScan();
            }
        });

        dialog.show();
    }

    private void kickCashDrawerViaBluetooth() {
        if (bluetoothManager.isConnected()) {
            // Direct hardware kick command sent to NT311
            boolean sent = bluetoothManager.kickCashDrawer();
            if (sent) {
                // Solenoid pulse feedback
                hardwareSimulator.kickBySolenoid(0, 50, 500);
            }
        } else {
            // If not connected to physical printer, simulate the kick locally
            addLog(new DrawerEvent(
                    DrawerEvent.Type.BT_KICK_SENT,
                    "Kick Drawer (Simulation Mode)",
                    "Bluetooth offline. Kicking virtual drawer solenoid pulse.",
                    "ESC/POS HEX: 1b700019fa",
                    false
            ));
            hardwareSimulator.kickBySolenoid(0, 50, 500);
            Toast.makeText(this, "Drawer Kicked (Connect NT311 for physical kick)", Toast.LENGTH_SHORT).show();
        }
    }

    private void queryBluetoothStatus() {
        if (bluetoothManager.isConnected()) {
            bluetoothManager.queryStatusRealtime();
        } else {
            byte rawStatus = hardwareSimulator.getRealtimeStatusByte();
            addLog(new DrawerEvent(
                    DrawerEvent.Type.BT_STATUS,
                    "Simulated Real-Time Status Byte (DLE EOT 1)",
                    String.format("Bit 2 = %d (Drawer %s)",
                            hardwareSimulator.isOpen() ? 1 : 0,
                            hardwareSimulator.isOpen() ? "OPEN" : "CLOSED"),
                    String.format("RAW HEX: 100401 -> RETURN: 0x%02X", rawStatus),
                    false
            ));
            Toast.makeText(this, "Queried local status byte", Toast.LENGTH_SHORT).show();
        }
    }

    private void printReceiptAndKickBluetooth() {
        String receipt =
                "================================\n" +
                "       SUNMI cPad + NT311       \n" +
                "    BLUETOOTH POS RECEIPT       \n" +
                "================================\n" +
                "Transaction: Cash Sale\n" +
                "Drawer Pin:  RJ12 Pin 2\n" +
                "Status:      Open Verified\n" +
                "Total:       $25.00\n" +
                "================================\n" +
                "       THANK YOU! VISIT AGAIN   \n";

        if (bluetoothManager.isConnected()) {
            bluetoothManager.printTestReceipt(receipt);
            bluetoothManager.kickCashDrawer();
            hardwareSimulator.kickBySolenoid(0, 50, 500);
        } else {
            addLog(new DrawerEvent(
                    DrawerEvent.Type.BT_KICK_SENT,
                    "Print & Kick (Simulation)",
                    "Sent simulated receipt & ESC p kick to buffer.",
                    "HEX: 1b40...1b700019fa",
                    false
            ));
            hardwareSimulator.kickBySolenoid(0, 50, 500);
            Toast.makeText(this, "Printed & Kicked (Simulated)", Toast.LENGTH_SHORT).show();
        }
    }

    // ==========================================
    // Bluetooth Manager Callbacks
    // ==========================================

    @Override
    public void onConnected(String deviceName, String targetDesc) {
        boolean isNet = (bluetoothManager.getConnectionType() == SunmiBluetoothManager.ConnectionType.NETWORK);
        String label = isNet ? ("● LAN: " + targetDesc) : ("● BT: " + deviceName);
        tvBtStatusPill.setText(label);
        tvBtStatusPill.setTextColor(Color.parseColor("#34D399"));
        tvBtStatusPill.setBackgroundColor(Color.parseColor("#064E3B"));

        tvDeviceHeaderLabel.setText(isNet ? ("NT311 WI-FI/LAN: " + targetDesc) : ("NT311 BLUETOOTH: " + deviceName));

        if (isNet) {
            btnConnectLan.setText("Disconnect LAN");
            btnConnectLan.setBackgroundColor(Color.parseColor("#991B1B"));
            btnConnectBt.setEnabled(false);
        } else {
            btnConnectBt.setText("Disconnect BT");
            btnConnectBt.setBackgroundColor(Color.parseColor("#991B1B"));
            btnConnectLan.setEnabled(false);
        }

        addLog(new DrawerEvent(
                DrawerEvent.Type.BT_CONNECTED,
                isNet ? "Wi-Fi / LAN Connected to NT311" : "Bluetooth Connected to NT311",
                isNet ? ("TCP RAW Socket (Port 9100) to " + targetDesc) : ("Paired & connected RFCOMM SPP channel to " + deviceName),
                "Target: " + targetDesc + " | ASB & Adaptive Polling Active",
                false
        ));
        Toast.makeText(this, (isNet ? "LAN Connected to " : "BT Connected to ") + targetDesc, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisconnected() {
        tvBtStatusPill.setText("● OFFLINE");
        tvBtStatusPill.setTextColor(Color.parseColor("#94A3B8"));
        tvBtStatusPill.setBackgroundColor(Color.parseColor("#1E293B"));

        tvDeviceHeaderLabel.setText("NT311 RJ12 CASHDRAWER");
        btnConnectBt.setText("📶 Bluetooth");
        btnConnectBt.setBackgroundColor(Color.parseColor("#0284C7"));
        btnConnectBt.setEnabled(true);

        btnConnectLan.setText("🌐 Wi-Fi / LAN");
        btnConnectLan.setBackgroundColor(Color.parseColor("#0D9488"));
        btnConnectLan.setEnabled(true);

        addLog(new DrawerEvent(
                DrawerEvent.Type.BT_DISCONNECTED,
                "Printer Disconnected",
                "Communication channel closed.",
                "",
                false
        ));
    }

    @Override
    public void onConnectionFailed(String error) {
        onDisconnected();
        addLog(new DrawerEvent(
                DrawerEvent.Type.ERROR,
                "Bluetooth Connection Failed",
                error != null ? error : "Failed to open RFCOMM socket",
                "Please verify printer is powered ON and paired in Android Settings.",
                false
        ));
        Toast.makeText(this, "BT Connect Failed: " + error, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onStatusByteReceived(byte statusByte, int bit2, boolean isDrawerOpen, boolean isCoverOpen, boolean isPaperOut, String statusSummary, long latencyMs) {
        // Real-time hardware status received from Sunmi NT311!
        if (isDrawerOpen != hardwareSimulator.isOpen()) {
            if (isDrawerOpen) {
                hardwareSimulator.manualOpen();
            } else {
                hardwareSimulator.manualClose();
            }
        }

        // Live telemetry updates
        if (tvSignalHealth != null) {
            String polStr = bluetoothManager.isInvertPolarity() ? "Inverted (N.C.)" : "Normal (N.O.)";
            tvSignalHealth.setText(String.format(Locale.getDefault(), "⚡ Pin 3: %s (Bit2=%d) | Polarity: %s | ASB+DLE EOT",
                    (bit2 == 1 ? "HIGH" : "LOW"), bit2, polStr));
        }
        if (tvSignalLatency != null) {
            tvSignalLatency.setText(latencyMs > 0 ? (latencyMs + "ms") : "<5ms");
        }

        addLog(new DrawerEvent(
                DrawerEvent.Type.BT_STATUS,
                "NT311 Hardware Status Decoded",
                statusSummary,
                String.format(Locale.getDefault(), "Status Byte: 0x%02X | Bit 2: %d (%s) | Latency: %dms", 
                        statusByte, 
                        bit2,
                        (bit2 == 1 ? "HIGH" : "LOW"),
                        latencyMs),
                false
        ));
    }

    @Override
    public void onPolarityAutoCalibrated(boolean isInverted, String reason) {
        updatePolarityButtonUI();
        Toast.makeText(this, "⚡ Polarity Auto-Calibrated: " + (isInverted ? "INVERTED (N.C.)" : "NORMAL (N.O.)"), Toast.LENGTH_LONG).show();
        addLog(new DrawerEvent(
                DrawerEvent.Type.STATUS_QUERY,
                "⚡ Smart Auto-Polarity Calibrated!",
                "Calibrated to: " + (isInverted ? "INVERTED (N.C. 0=Open, 1=Closed)" : "NORMAL (N.O. 1=Open, 0=Closed)"),
                reason,
                false
        ));
    }

    @Override
    public void onSensorDiagnosticWarning(String warning) {
        Toast.makeText(this, warning, Toast.LENGTH_LONG).show();
        addLog(new DrawerEvent(
                DrawerEvent.Type.ALERT_TAMPER,
                "Hardware Sensor Warning",
                warning,
                "Check RJ12 6-pin cable and mechanical key lock position",
                true
        ));
    }

    @Override
    public void onCommandSent(String hexCommand, String description) {
        addLog(new DrawerEvent(
                DrawerEvent.Type.BT_KICK_SENT,
                description,
                "Dispatched to NT311 over Bluetooth SPP",
                "HEX: " + hexCommand,
                false
        ));
    }

    // ==========================================
    // Hardware Simulator Callbacks (Visuals & Watchdog)
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
                "Threshold: " + hardwareSimulator.getTimeoutThresholdSec() + "s exceeded. Sounding buzzer.",
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

    private void showCashDrawerReportDialog() {
        final CashDrawerReportGenerator.ReportSummary summary = CashDrawerReportGenerator.computeSummary(
                logList,
                hardwareSimulator.isOpen(),
                bluetoothManager.isInvertPolarity(),
                bluetoothManager.getConnectedDeviceName(),
                SunmiCloudPrinter.DEFAULT_SN
        );

        final String textReport = CashDrawerReportGenerator.generateTextReport(summary);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_drawer_report, null);
        builder.setView(view);

        final AlertDialog dialog = builder.create();

        TextView tvGenerated = view.findViewById(R.id.tvReportGeneratedAt);
        TextView tvOpens = view.findViewById(R.id.tvReportOpens);
        TextView tvCloses = view.findViewById(R.id.tvReportCloses);
        TextView tvAlerts = view.findViewById(R.id.tvReportAlerts);
        TextView tvAvgDuration = view.findViewById(R.id.tvReportAvgDuration);
        TextView tvSubMetrics = view.findViewById(R.id.tvReportSubMetrics);
        TextView tvFullText = view.findViewById(R.id.tvReportFullText);

        tvGenerated.setText(summary.generatedAt);
        tvOpens.setText(String.valueOf(summary.totalOpens));
        tvCloses.setText(String.valueOf(summary.totalCloses));
        tvAlerts.setText(String.valueOf(summary.overtimeAlerts));
        tvAvgDuration.setText(String.format(Locale.getDefault(), "%.1fs", summary.averageOpenDurationMs / 1000.0f));
        tvSubMetrics.setText(String.format(Locale.getDefault(),
                "Kicks: BT=%d, Cloud=%d, Manual=%d | Max Open: %.1fs | %s",
                summary.solenoidKicks, summary.cloudKicks, summary.manualOpens,
                summary.maxOpenDurationMs / 1000.0f,
                summary.isInvertedPolarity ? "Inverted (N.C.)" : "Normal (N.O.)"));
        tvFullText.setText(textReport);

        // Print to NT311 Button
        view.findViewById(R.id.btnPrintReportBt).setOnClickListener(v -> {
            if (bluetoothManager.isConnected()) {
                byte[] escPosBytes = CashDrawerReportGenerator.generateEscPosReport(summary);
                boolean sent = bluetoothManager.printEscPosReport(escPosBytes);
                if (sent) {
                    addLog(new DrawerEvent(
                            DrawerEvent.Type.STATUS_QUERY,
                            "Cash Drawer Report Printed",
                            "ESC/POS Audit Report dispatched to NT311 (" + escPosBytes.length + " bytes)",
                            "Thermal paper printed with usage metrics & security audit",
                            false
                    ));
                    Toast.makeText(this, "Printing report to NT311...", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to send print job to printer", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Bluetooth printer not connected. Connect NT311 to print.", Toast.LENGTH_LONG).show();
            }
        });

        // Share Report Button
        view.findViewById(R.id.btnShareReport).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "Sunmi Cash Drawer Audit Report - " + summary.generatedAt);
            intent.putExtra(Intent.EXTRA_TEXT, textReport);
            startActivity(Intent.createChooser(intent, "Share Cash Drawer Audit Report"));
        });

        // Copy Text Button
        view.findViewById(R.id.btnCopyReportText).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CashDrawerReport", textReport));
            Toast.makeText(this, "Report copied to clipboard", Toast.LENGTH_SHORT).show();
        });

        // Close Button
        view.findViewById(R.id.btnCloseReport).setOnClickListener(v -> dialog.dismiss());

        dialog.show();

        addLog(new DrawerEvent(
                DrawerEvent.Type.STATUS_QUERY,
                "Audit Report Generated",
                String.format(Locale.getDefault(), "Summary compiled: %d opens, %d alerts, avg %.1fs",
                        summary.totalOpens, summary.overtimeAlerts, summary.averageOpenDurationMs / 1000.0f),
                null,
                false
        ));
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Sunmi Cloud & Hardware Config");

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
        if (lanScanner != null && lanScanner.isScanning()) {
            lanScanner.cancelScan();
        }
        if (bluetoothManager != null) {
            bluetoothManager.disconnect();
        }
        if (webhookServer != null) {
            webhookServer.stop();
        }
    }
}
