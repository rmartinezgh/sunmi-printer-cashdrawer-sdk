package com.sunmi.cashdrawer.simulator;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sunmi Bluetooth Manager for connecting Sunmi cPad to Sunmi NT311 Printer.
 * Supports configurable Pin 3 switch polarity (Normally Open / Normally Closed).
 * Aligned with official Sunmi externalprinterlibrary2 SDK.
 */
public class SunmiBluetoothManager implements SunmiCashDrawerApi {

    // Standard Bluetooth Serial Port Profile (SPP) UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Official Sunmi EscUtil Command Constants
    public static final byte[] CMD_OPEN_CASH_SUNMI = new byte[] { 0x1B, 0x70, 0x00, (byte) 0xC8, (byte) 0xC8 }; // 1b7000c8c8
    public static final byte[] CMD_OPEN_CASH_LEGACY = new byte[] { 0x1B, 0x70, 0x00, 0x19, (byte) 0xFA };        // 1b700019fa
    public static final byte[] CMD_REAL_TIME = new byte[] { 0x10, 0x04, 0x01 };                                   // 100401
    public static final byte[] CMD_CUSTOM_STATUS = new byte[] { 0x1D, 0x28, 0x54, 0x01, 0x00, 0x01 };             // 1d2854010001
    public static final byte[] CMD_CUSTOM_STATUS_STABLE = new byte[] { 0x1D, 0x28, 0x54, 0x01, 0x00, 0x09 };      // 1d2854010009
    public static final byte[] CMD_ASB_ENABLE = new byte[] { 0x1D, 0x61, 0x01 };                                  // 1d6101

    public interface BluetoothCallback {
        void onConnected(String deviceName, String macAddress);
        void onDisconnected();
        void onConnectionFailed(String error);
        void onStatusByteReceived(byte statusByte, boolean isDrawerOpen, boolean isCoverOpen, boolean isPaperOut, String statusSummary);
        void onCommandSent(String hexCommand, String description);
    }

    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread workerThread;
    private volatile boolean isConnected = false;
    private String connectedDeviceName = "Sunmi NT311";

    // Polarity inversion for RJ12 Pin 3 switch:
    // Default true: Bit 2 == 0 -> OPEN, Bit 2 == 1 -> CLOSED (matches standard NT311 N.C. cash drawer)
    private boolean invertPolarity = true;

    private int openCount = 0;
    private int currentDrawerStatus = 0; // 0: Closed, 1: Open

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothCallback callback;

    private boolean isPolling = false;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isConnected && isPolling) {
                queryStatusRealtime();
                pollHandler.postDelayed(this, 1000); // Poll every 1 second
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
        return isConnected && socket != null && socket.isConnected();
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
    // Bluetooth Connection & Data Handling
    // ==========================================

    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getPairedDevices() {
        List<BluetoothDevice> list = new ArrayList<>();
        if (bluetoothAdapter != null) {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            if (paired != null) {
                list.addAll(paired);
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

                final String name = device.getName() != null ? device.getName() : "Sunmi NT311";
                final String addr = device.getAddress();
                connectedDeviceName = name;

                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onConnected(name, addr);
                    }
                });

                // Enable Automatic Status Back (ASB) on printer
                enableAutoStatusBack();

                // Start listening for incoming status bytes
                startListeningThread();

                // Start periodic real-time status polling
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

        inputStream = null;
        outputStream = null;
        socket = null;

        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDisconnected();
            }
        });
    }

    private void startListeningThread() {
        workerThread = new Thread(() -> {
            byte[] buffer = new byte[64];
            while (isConnected && !Thread.currentThread().isInterrupted()) {
                try {
                    if (inputStream == null) break;
                    int read = inputStream.read(buffer);
                    if (read > 0) {
                        // Official Sunmi StatusTask.getStatus decoding:
                        final byte statusByte;
                        if (read == 1) {
                            statusByte = buffer[0];
                        } else if (read >= 4 && (buffer[0] & 0xFF) == 0x37 && (buffer[1] & 0xFF) == 0x09) {
                            statusByte = buffer[2];
                        } else {
                            statusByte = buffer[0];
                        }

                        // Bit 2: Cash drawer sensor on Pin 3
                        int bit2 = (statusByte >> 2) & 0x01;

                        // Correct polarity mapping:
                        // When invertPolarity is true: bit2 == 0 -> OPEN, bit2 == 1 -> CLOSED
                        // When invertPolarity is false: bit2 == 1 -> OPEN, bit2 == 0 -> CLOSED
                        final boolean isOpen = invertPolarity ? (bit2 == 0) : (bit2 == 1);

                        // Other status bits
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
                        summary.append(String.format(" [Bit2=%d, Polarity=%s]", bit2, invertPolarity ? "Inverted" : "Normal"));
                        if (isCoverOpen) summary.append(", COVER_OPEN");
                        if (isPaperOut) summary.append(", OUT_PAPER");
                        if (isJam) summary.append(", JAM_PAPER");

                        mainHandler.post(() -> {
                            if (callback != null) {
                                callback.onStatusByteReceived(statusByte, isOpen, isCoverOpen, isPaperOut, summary.toString());
                            }
                        });
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

    public void startStatusPolling() {
        isPolling = true;
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.postDelayed(pollRunnable, 500);
    }

    public void stopStatusPolling() {
        isPolling = false;
        pollHandler.removeCallbacks(pollRunnable);
    }

    /**
     * Send raw ESC/POS bytes over Bluetooth
     */
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
        return sendBytes(CMD_OPEN_CASH_SUNMI, "Sunmi EscUtil.openCash() (Pin 2, 1B 70 00 C8 C8)");
    }

    /**
     * [DLE EOT 1]: Official Sunmi EscUtil.REAL_TIME (10 04 01)
     */
    public boolean queryStatusRealtime() {
        return sendBytes(CMD_REAL_TIME, "Sunmi EscUtil.REAL_TIME (DLE EOT 1: 10 04 01)");
    }

    /**
     * [GS ( T 1 0 1]: Official Sunmi EscUtil.customQueryStatus() (1D 28 54 01 00 01)
     */
    public boolean queryCustomStatus() {
        return sendBytes(CMD_CUSTOM_STATUS, "Sunmi EscUtil.customQueryStatus() (1D 28 54 01 00 01)");
    }

    /**
     * [GS a 1]: Enable Automatic Status Back (1D 61 01)
     */
    public boolean enableAutoStatusBack() {
        return sendBytes(CMD_ASB_ENABLE, "GS a 1 Enable Automatic Status Back");
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
