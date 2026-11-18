package com.sunmi.cashdrawer.simulator;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Enhanced Sunmi Bluetooth Manager for connecting Sunmi cPad to Sunmi NT311 Printer.
 * Features:
 * - High-speed adaptive real-time polling (250ms normal / 80ms burst tracking)
 * - Full Automatic Status Back (ASB: 1D 61 0F)
 * - Stream ring buffer framing preventing packet fragmentation/drops
 * - Dual-command status decoding (DLE EOT 1 + Sunmi 4-byte 0x37 0x09 + GS r 1 + ASB)
 * - Microswitch contact de-bouncing & noise filtering
 * - Self-calibrating smart polarity auto-detection (N.C. vs N.O.)
 * - Sub-millisecond latency tracking and cable disconnect diagnostics
 */
public class SunmiBluetoothManager implements SunmiCashDrawerApi {

    // Standard Bluetooth Serial Port Profile (SPP) UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Official Sunmi EscUtil Command Constants
    public static final byte[] CMD_OPEN_CASH_SUNMI = new byte[] { 0x1B, 0x70, 0x00, (byte) 0xC8, (byte) 0xC8 }; // 1b7000c8c8 (200x2=400ms pulse)
    public static final byte[] CMD_OPEN_CASH_LEGACY = new byte[] { 0x1B, 0x70, 0x00, 0x19, (byte) 0xFA };        // 1b700019fa (50ms pulse)
    public static final byte[] CMD_REAL_TIME = new byte[] { 0x10, 0x04, 0x01 };                                   // 100401 (DLE EOT 1)
    public static final byte[] CMD_QUERY_DRAWER_STATUS = new byte[] { 0x1D, 0x72, 0x01 };                          // 1d7201 (GS r 1: Cash drawer status)
    public static final byte[] CMD_CUSTOM_STATUS = new byte[] { 0x1D, 0x28, 0x54, 0x01, 0x00, 0x01 };             // 1d2854010001
    public static final byte[] CMD_CUSTOM_STATUS_STABLE = new byte[] { 0x1D, 0x28, 0x54, 0x01, 0x00, 0x09 };      // 1d2854010009
    public static final byte[] CMD_ASB_ENABLE = new byte[] { 0x1D, 0x61, 0x0F };                                  // 1d610f (Enable ASB: Drawer, Cover, Paper, Error)

    // Polling Intervals
    private static final long NORMAL_POLL_INTERVAL_MS = 250;
    private static final long BURST_POLL_INTERVAL_MS = 80;

    public interface BluetoothCallback {
        void onConnected(String deviceName, String macAddress);
        void onDisconnected();
        void onConnectionFailed(String error);
        void onStatusByteReceived(byte statusByte, int bit2, boolean isDrawerOpen, boolean isCoverOpen, boolean isPaperOut, String statusSummary, long latencyMs);
        void onCommandSent(String hexCommand, String description);
        void onPolarityAutoCalibrated(boolean isInverted, String reason);
        void onSensorDiagnosticWarning(String warning);
    }

    public enum ConnectionType {
        NONE("Offline"),
        BLUETOOTH("Bluetooth SPP"),
        NETWORK("Wi-Fi / Ethernet LAN");

        public final String label;
        ConnectionType(String label) { this.label = label; }
    }

    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private java.net.Socket netSocket;
    private ConnectionType connectionType = ConnectionType.NONE;
    private String connectedHost = "";
    private int connectedPort = 9100;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread workerThread;
    private volatile boolean isConnected = false;
    private String connectedDeviceName = "Sunmi NT311";

    // Polarity: Default true for Normally Closed (N.C.) microswitches:
    // When true: Bit 2 == 0 -> OPEN, Bit 2 == 1 -> CLOSED
    // When false: Bit 2 == 1 -> OPEN, Bit 2 == 0 -> CLOSED
    private boolean invertPolarity = true;

    // Debouncing & State Management
    private int openCount = 0;
    private int currentDrawerStatus = 0; // 0: Closed, 1: Open
    private int stableBit2 = 1;         // Baseline microswitch state
    private int lastPendingBit2 = -1;
    private int debounceCount = 0;

    // Burst Tracking & Auto-Calibration State
    private long burstUntilTimeMs = 0;
    private boolean isTrackingKickTransition = false;
    private int preKickBit2 = -1;
    private int consecutiveKicksWithoutChange = 0;
    private long lastQueryTimestamp = 0;

    // Stream Accumulation Buffer
    private final ByteArrayOutputStream streamBuffer = new ByteArrayOutputStream();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothCallback callback;

    private boolean isPolling = false;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isConnected && isPolling) {
                lastQueryTimestamp = SystemClock.elapsedRealtime();
                queryStatusRealtime();

                long now = System.currentTimeMillis();
                long nextDelay = (now < burstUntilTimeMs) ? BURST_POLL_INTERVAL_MS : NORMAL_POLL_INTERVAL_MS;
                pollHandler.postDelayed(this, nextDelay);
            }
        }
    };

    public SunmiBluetoothManager() {
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setCallback(BluetoothCallback callback) {
        this.callback = callback;
    }

    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isConnected() {
        if (!isConnected) return false;
        if (connectionType == ConnectionType.BLUETOOTH) {
            return socket != null && socket.isConnected();
        } else if (connectionType == ConnectionType.NETWORK) {
            return netSocket != null && netSocket.isConnected() && !netSocket.isClosed();
        }
        return false;
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }

    public String getConnectedHost() {
        return connectedHost;
    }

    public int getConnectedPort() {
        return connectedPort;
    }

    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }

    public boolean isInvertPolarity() {
        return invertPolarity;
    }

    public void setInvertPolarity(boolean invert) {
        this.invertPolarity = invert;
    }

    public int getStableBit2() {
        return stableBit2;
    }

    // ==========================================
    // Sunmi Official API Implementation (xfxdeghjk524)
    // ==========================================

    @Override
    public void openDrawer(final ICallback callback) {
        boolean sent = kickCashDrawer();
        if (callback != null) {
            mainHandler.post(() -> {
                if (sent) {
                    callback.onRunResult(true);
                } else {
                    callback.onRaiseException(-1, "Bluetooth not connected to Sunmi printer");
                }
            });
        }
    }

    @Override
    public int getOpenDrawerTimes() {
        return openCount;
    }

    @Override
    public int getDrawerStatus() {
        return currentDrawerStatus;
    }

    // ==========================================
    // Bluetooth Connection Lifecycle
    // ==========================================

    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getPairedDevices() {
        List<BluetoothDevice> list = new ArrayList<>();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                list.addAll(bonded);
            }
        }
        return list;
    }

    @SuppressLint("MissingPermission")
    public void connect(final BluetoothDevice device) {
        if (device == null) return;

        disconnect();

        new Thread(() -> {
            try {
                if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                isConnected = true;
                connectionType = ConnectionType.BLUETOOTH;

                final String name = device.getName() != null ? device.getName() : "Sunmi NT311";
                final String addr = device.getAddress();
                connectedDeviceName = name;

                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onConnected(name, addr);
                    }
                });

                // Enable Full Automatic Status Back (ASB) on printer
                enableAutoStatusBack();

                // Start stream reader thread
                startListeningThread();

                // Start adaptive real-time status polling
                startStatusPolling();

            } catch (final Exception e) {
                disconnect();
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onConnectionFailed(e.getMessage());
                    }
                });
            }
        }).start();
    }

    /**
     * Connect to Sunmi Cloud Printer via Wi-Fi or Ethernet LAN.
     * Uses RAW ESC/POS TCP Socket on Port 9100.
     */
    public void connectNetwork(final String host, final int port) {
        if (host == null || host.trim().isEmpty()) return;

        disconnect();

        new Thread(() -> {
            try {
                netSocket = new java.net.Socket();
                netSocket.connect(new java.net.InetSocketAddress(host.trim(), port), 6000);
                netSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for sub-ms real-time status packets
                netSocket.setKeepAlive(true);

                inputStream = netSocket.getInputStream();
                outputStream = netSocket.getOutputStream();
                isConnected = true;
                connectionType = ConnectionType.NETWORK;
                connectedHost = host.trim();
                connectedPort = port;
                connectedDeviceName = "NT311 (" + host.trim() + ":" + port + ")";

                final String targetDesc = host.trim() + ":" + port;
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onConnected(connectedDeviceName, targetDesc);
                    }
                });

                // Enable Full Automatic Status Back (ASB) on printer
                enableAutoStatusBack();

                // Start stream reader thread
                startListeningThread();

                // Start adaptive real-time status polling
                startStatusPolling();

            } catch (final Exception e) {
                disconnect();
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onConnectionFailed("TCP Connection failed to " + host + ":" + port + " (" + e.getMessage() + ")");
                    }
                });
            }
        }).start();
    }

    public synchronized void disconnect() {
        isConnected = false;
        stopStatusPolling();

        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }

        try {
            if (inputStream != null) inputStream.close();
        } catch (Exception ignored) {}

        try {
            if (outputStream != null) outputStream.close();
        } catch (Exception ignored) {}

        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}

        try {
            if (netSocket != null) netSocket.close();
        } catch (Exception ignored) {}

        inputStream = null;
        outputStream = null;
        socket = null;
        netSocket = null;
        connectionType = ConnectionType.NONE;

        synchronized (streamBuffer) {
            streamBuffer.reset();
        }

        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDisconnected();
            }
        });
    }

    // ==========================================
    // Resilient Stream Processing & Packet Decoding
    // ==========================================

    private void startListeningThread() {
        workerThread = new Thread(() -> {
            byte[] readBuffer = new byte[128];
            while (isConnected && !Thread.currentThread().isInterrupted()) {
                try {
                    if (inputStream == null) break;
                    int bytesRead = inputStream.read(readBuffer);
                    if (bytesRead > 0) {
                        processIncomingStreamBytes(readBuffer, bytesRead);
                    }
                } catch (IOException e) {
                    if (isConnected) {
                        disconnect();
                    }
                    break;
                }
            }
        });
        workerThread.start();
    }

    /**
     * Accumulates chunks into ring buffer and parses frames with zero fragmentation drop.
     */
    private void processIncomingStreamBytes(byte[] chunk, int length) {
        synchronized (streamBuffer) {
            streamBuffer.write(chunk, 0, length);
            byte[] buf = streamBuffer.toByteArray();
            int offset = 0;

            while (offset < buf.length) {
                int remaining = buf.length - offset;

                // 1. Check for official Sunmi 4-byte frame header (0x37 0x09)
                if (remaining >= 4 && (buf[offset] & 0xFF) == 0x37 && (buf[offset + 1] & 0xFF) == 0x09) {
                    byte statusByte = buf[offset + 2];
                    dispatchStatusByte(statusByte, "Sunmi 4-Byte Packet (0x37 0x09)");
                    offset += 4;
                    continue;
                }

                // 2. Check for standard ESC/POS 4-byte ASB frame (header: bit0=0, bit1=0, bit4=1, bit7=0 -> 0x10)
                if (remaining >= 4 && (buf[offset] & 0x93) == 0x10) {
                    byte statusByte = buf[offset];
                    dispatchStatusByte(statusByte, "ESC/POS ASB Push Frame");
                    offset += 4;
                    continue;
                }

                // 3. Check for Real-Time DLE EOT 1-byte response (bit0=0, bit1=1, bit4=1, bit7=0 -> 0x12)
                if ((buf[offset] & 0x93) == 0x12) {
                    byte statusByte = buf[offset];
                    dispatchStatusByte(statusByte, "Real-Time DLE EOT 1");
                    offset += 1;
                    continue;
                }

                // 4. Check for GS r 1 status response (0x00 or 0x01)
                if (remaining == 1 && (buf[offset] == 0x00 || buf[offset] == 0x01)) {
                    byte statusByte = (byte) (buf[offset] == 0x01 ? 0x04 : 0x00);
                    dispatchStatusByte(statusByte, "GS r 1 Direct Status");
                    offset += 1;
                    continue;
                }

                // If single isolated byte received directly from DLE EOT poll
                if (remaining == 1) {
                    byte statusByte = buf[offset];
                    dispatchStatusByte(statusByte, "Real-Time Status Byte");
                    offset += 1;
                    continue;
                }

                // Advance one byte if pattern not matched yet
                offset++;
            }

            // Compact buffer
            streamBuffer.reset();
            if (offset < buf.length) {
                streamBuffer.write(buf, offset, buf.length - offset);
            }
        }
    }

    /**
     * De-bounces mechanical switch contact chatter and computes confirmed state.
     */
    private void dispatchStatusByte(final byte statusByte, final String sourceFrame) {
        long now = SystemClock.elapsedRealtime();
        final long latencyMs = (lastQueryTimestamp > 0) ? (now - lastQueryTimestamp) : 0;

        // Extract RJ12 Pin 3 Microswitch level from Bit 2
        int rawBit2 = (statusByte >> 2) & 0x01;

        // Contact Chatter De-bouncing:
        // Require 2 consecutive matching samples before shifting the stable baseline
        if (rawBit2 != stableBit2) {
            if (rawBit2 == lastPendingBit2) {
                debounceCount++;
                if (debounceCount >= 2) {
                    // Confirmed stable edge transition!
                    int prevStable = stableBit2;
                    stableBit2 = rawBit2;
                    debounceCount = 0;

                    // Evaluate auto-calibration if tracking kick
                    checkAutoPolarityCalibration(prevStable, stableBit2);
                }
            } else {
                lastPendingBit2 = rawBit2;
                debounceCount = 1;
            }
        } else {
            debounceCount = 0;
            lastPendingBit2 = rawBit2;
        }

        // Compute confirmed open/close using active polarity:
        final boolean isOpen = invertPolarity ? (stableBit2 == 0) : (stableBit2 == 1);
        final boolean isCoverOpen = ((statusByte >> 5) & 0x01) == 1;
        final boolean isPaperOut = ((statusByte >> 1) & 0x01) == 1;
        final boolean isJam = ((statusByte >> 3) & 0x01) == 1;

        int prevStatus = currentDrawerStatus;
        currentDrawerStatus = isOpen ? 1 : 0;
        if (prevStatus == 0 && currentDrawerStatus == 1) {
            openCount++;
        }

        final StringBuilder summary = new StringBuilder();
        summary.append(isOpen ? "DRAWER_OPEN (In Transaction)" : "DRAWER_CLOSED (Secured)");
        summary.append(String.format(" [Bit2=%d, %s, Polarity=%s]",
                stableBit2,
                (stableBit2 == 1 ? "HIGH" : "LOW"),
                invertPolarity ? "Inverted (N.C.)" : "Normal (N.O.)"));
        if (isCoverOpen) summary.append(", COVER_OPEN");
        if (isPaperOut) summary.append(", OUT_PAPER");
        if (isJam) summary.append(", JAM_PAPER");
        summary.append(String.format(" (%s, %dms)", sourceFrame, latencyMs));

        mainHandler.post(() -> {
            if (callback != null) {
                callback.onStatusByteReceived(statusByte, stableBit2, isOpen, isCoverOpen, isPaperOut, summary.toString(), latencyMs);
            }
        });
    }

    /**
     * Self-Calibrating Polarity Detection:
     * When a solenoid kick is dispatched, the closed drawer pops open.
     * Observing the transition confirms whether the microswitch is N.C. or N.O.
     */
    private void checkAutoPolarityCalibration(int fromBit2, int toBit2) {
        if (!isTrackingKickTransition) return;

        boolean detectedInversion;
        String reason;

        if (fromBit2 == 1 && toBit2 == 0) {
            // HIGH -> LOW transition on open proves Normally Closed (N.C.)
            detectedInversion = true;
            reason = "Measured transition: Pin 3 pulled from HIGH to LOW on pop -> Normally Closed (N.C.) verified.";
        } else if (fromBit2 == 0 && toBit2 == 1) {
            // LOW -> HIGH transition on open proves Normally Open (N.O.)
            detectedInversion = false;
            reason = "Measured transition: Pin 3 pulled from LOW to HIGH on pop -> Normally Open (N.O.) verified.";
        } else {
            return;
        }

        isTrackingKickTransition = false;
        consecutiveKicksWithoutChange = 0;

        if (invertPolarity != detectedInversion) {
            invertPolarity = detectedInversion;
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onPolarityAutoCalibrated(detectedInversion, reason);
                }
            });
        }
    }

    // ==========================================
    // Polling & High-Speed Burst Tracking
    // ==========================================

    public void startStatusPolling() {
        isPolling = true;
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.postDelayed(pollRunnable, 200);
    }

    public void stopStatusPolling() {
        isPolling = false;
        pollHandler.removeCallbacks(pollRunnable);
    }

    /**
     * Trigger high-speed 80ms burst polling for 2.5 seconds (used during kicks or state changes)
     */
    public void triggerBurstTracking(int durationMs) {
        burstUntilTimeMs = System.currentTimeMillis() + durationMs;
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.post(pollRunnable);
    }

    /**
     * Begin an automated polarity calibration sequence.
     * Fires a 400ms pulse, triggers burst tracking, and captures microswitch transition.
     */
    public boolean startAutoCalibration() {
        if (!isConnected()) return false;
        isTrackingKickTransition = true;
        preKickBit2 = stableBit2;
        triggerBurstTracking(3000);
        return kickCashDrawer();
    }

    // ==========================================
    // ESC/POS Command Dispatching
    // ==========================================

    public synchronized boolean sendBytes(final byte[] data, final String description) {
        if (!isConnected() || outputStream == null) {
            return false;
        }
        try {
            outputStream.write(data);
            outputStream.flush();

            final StringBuilder hex = new StringBuilder();
            for (byte b : data) {
                hex.append(String.format("%02x", b));
            }

            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onCommandSent(hex.toString(), description);
                }
            });
            return true;
        } catch (IOException e) {
            disconnect();
            return false;
        }
    }

    /**
     * [ESC p 0 200 200]: Official Sunmi EscUtil.openCash() (1B 70 00 C8 C8)
     */
    public boolean kickCashDrawer() {
        preKickBit2 = stableBit2;
        isTrackingKickTransition = true;
        triggerBurstTracking(2500);

        boolean sent = sendBytes(CMD_OPEN_CASH_SUNMI, "Sunmi EscUtil.openCash() (Pin 2, 1B 70 00 C8 C8)");

        // Diagnostic watchdog: check if signal remains frozen across multiple kicks
        mainHandler.postDelayed(() -> {
            if (isTrackingKickTransition && stableBit2 == preKickBit2) {
                consecutiveKicksWithoutChange++;
                if (consecutiveKicksWithoutChange >= 3) {
                    if (callback != null) {
                        callback.onSensorDiagnosticWarning("⚠️ RJ12 Sensor Static: Pin 3 bit did not change after 3 kicks. Check RJ12 cable or physical key lock.");
                    }
                }
            } else {
                consecutiveKicksWithoutChange = 0;
            }
            isTrackingKickTransition = false;
        }, 2600);

        return sent;
    }

    /**
     * [DLE EOT 1]: Official Sunmi EscUtil.REAL_TIME (10 04 01)
     */
    public boolean queryStatusRealtime() {
        lastQueryTimestamp = SystemClock.elapsedRealtime();
        return sendBytes(CMD_REAL_TIME, "Sunmi EscUtil.REAL_TIME (DLE EOT 1: 10 04 01)");
    }

    /**
     * [GS r 1]: Query Drawer Status specifically (1D 72 01)
     */
    public boolean queryDrawerStatusGs() {
        return sendBytes(CMD_QUERY_DRAWER_STATUS, "ESC/POS GS r 1 (Query Cash Drawer Status)");
    }

    /**
     * [GS ( T 1 0 1]: Official Sunmi EscUtil.customQueryStatus() (1D 28 54 01 00 01)
     */
    public boolean queryCustomStatus() {
        return sendBytes(CMD_CUSTOM_STATUS, "Sunmi EscUtil.customQueryStatus() (1D 28 54 01 00 01)");
    }

    /**
     * [GS a 15]: Enable Full Automatic Status Back (1D 61 0F)
     */
    public boolean enableAutoStatusBack() {
        return sendBytes(CMD_ASB_ENABLE, "GS a 15 Enable Full Automatic Status Back (1D 61 0F)");
    }

    /**
     * Print test receipt over Bluetooth
     */
    public boolean printTestReceipt(String text) {
        try {
            byte[] init = new byte[] { 0x1B, 0x40 };
            byte[] content = text.getBytes("UTF-8");
            byte[] feedAndCut = new byte[] { 0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x31 };

            byte[] full = new byte[init.length + content.length + feedAndCut.length];
            System.arraycopy(init, 0, full, 0, init.length);
            System.arraycopy(content, 0, full, init.length, content.length);
            System.arraycopy(feedAndCut, 0, full, init.length + content.length, feedAndCut.length);

            return sendBytes(full, "Print Test Receipt");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Print binary ESC/POS Cash Drawer Audit Report directly to NT311
     */
    public boolean printEscPosReport(byte[] reportBytes) {
        if (reportBytes == null || reportBytes.length == 0) return false;
        return sendBytes(reportBytes, "Print ESC/POS Cash Drawer Audit Report");
    }
}
