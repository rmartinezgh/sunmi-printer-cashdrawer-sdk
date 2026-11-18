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
 * Manages Bluetooth SPP connection between Sunmi cPad and Sunmi NT311 Printer.
 * Supports:
 * - Direct ESC/POS Cash Drawer Kick (1B 70 00 19 FA)
 * - Real-Time Status Query (DLE EOT 1: 10 04 01)
 * - Automatic Status Back (ASB: 1D 61 01)
 * - Continuous Background Status Polling & Pin 3 switch detection
 */
public class SunmiBluetoothManager {

    // Standard Bluetooth Serial Port Profile (SPP) UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface BluetoothCallback {
        void onConnected(String deviceName, String macAddress);
        void onDisconnected();
        void onConnectionFailed(String error);
        void onStatusByteReceived(byte statusByte, boolean isDrawerOpen, boolean isCoverOpen, boolean isPaperOut);
        void onCommandSent(String hexCommand, String description);
    }

    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread workerThread;
    private volatile boolean isConnected = false;

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
                        for (int i = 0; i < read; i++) {
                            final byte b = buffer[i];

                            // DLE EOT 1 status byte parser:
                            // Bit 2: Cash drawer sensor (1 = Open, 0 = Closed)
                            // Bit 5: Cover sensor (1 = Open, 0 = Closed)
                            // Bit 6: Paper sensor (1 = Out of paper, 0 = Present)
                            final boolean isOpen = (b & 0x04) != 0;
                            final boolean isCoverOpen = (b & 0x20) != 0;
                            final boolean isPaperOut = (b & 0x40) != 0;

                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onStatusByteReceived(b, isOpen, isCoverOpen, isPaperOut);
                                }
                            });
                        }
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
     * [ESC p 0 25 250]: Kick Cash Drawer via Bluetooth (1B 70 00 19 FA)
     */
    public boolean kickCashDrawer() {
        byte[] kickCmd = new byte[] { 0x1B, 0x70, 0x00, 0x19, (byte) 0xFA };
        return sendBytes(kickCmd, "Kick Cash Drawer (Pin 2, 50ms pulse)");
    }

    /**
     * [DLE EOT 1]: Query Real-time Drawer & Printer Status (10 04 01)
     */
    public boolean queryStatusRealtime() {
        byte[] queryCmd = new byte[] { 0x10, 0x04, 0x01 };
        return sendBytes(queryCmd, "DLE EOT 1 Real-time Status Query");
    }

    /**
     * [GS a 1]: Enable Automatic Status Back (1D 61 01)
     */
    public boolean enableAutoStatusBack() {
        byte[] asbCmd = new byte[] { 0x1D, 0x61, 0x01 };
        return sendBytes(asbCmd, "GS a 1 Enable Automatic Status Back");
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
}
