package com.sunmi.cashdrawer.simulator;

import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fast asynchronous LAN scanner for discovering Sunmi Cloud Printers on Wi-Fi and Ethernet subnets.
 * Probes TCP port 9100 (Standard RAW ESC/POS printer port).
 */
public class SunmiLanScanner {

    public interface ScanCallback {
        void onPrinterDiscovered(String ip, int port, String modelDescription);
        void onScanProgress(int progress, int total, String currentIp);
        void onScanCompleted(List<String> discoveredIps);
    }

    private static final int DEFAULT_ESC_POS_PORT = 9100;
    private static final int PROBE_TIMEOUT_MS = 380;
    private static final int THREAD_POOL_SIZE = 28;

    private ExecutorService executor;
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public boolean isScanning() {
        return isScanning.get();
    }

    public synchronized void cancelScan() {
        isScanning.set(false);
        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (Exception ignored) {}
            executor = null;
        }
    }

    public void startScan(final ScanCallback callback) {
        cancelScan();
        isScanning.set(true);

        final List<String> discoveredList = new ArrayList<>();
        final ExecutorService currentExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        synchronized (this) {
            this.executor = currentExecutor;
        }

        new Thread(() -> {
            try {
                String localIp = getLocalIpAddress();
                String subnetPrefix;

                if (localIp != null && localIp.contains(".")) {
                    int lastDot = localIp.lastIndexOf('.');
                    subnetPrefix = localIp.substring(0, lastDot + 1);
                } else {
                    // Fallback default from Sunmi setup guide
                    subnetPrefix = "192.168.103.";
                }

                final List<String> targetsToProbe = new ArrayList<>();

                // Priority 1: Common Sunmi default IPs from setup guide
                String[] knownSunmiIps = new String[]{
                        subnetPrefix + "134",
                        subnetPrefix + "166",
                        subnetPrefix + "181",
                        subnetPrefix + "221",
                        subnetPrefix + "100",
                        "192.168.103.134",
                        "192.168.103.166",
                        "192.168.103.181"
                };
                for (String known : knownSunmiIps) {
                    if (!targetsToProbe.contains(known)) {
                        targetsToProbe.add(known);
                    }
                }

                // Priority 2: Full local subnet 1..254
                for (int i = 1; i <= 254; i++) {
                    String ip = subnetPrefix + i;
                    if (!targetsToProbe.contains(ip)) {
                        targetsToProbe.add(ip);
                    }
                }

                final int totalTargets = targetsToProbe.size();
                final AtomicInteger completedCount = new AtomicInteger(0);

                for (final String targetIp : targetsToProbe) {
                    if (!isScanning.get() || currentExecutor.isShutdown()) break;

                    try {
                        currentExecutor.execute(() -> {
                            if (!isScanning.get()) return;

                            boolean isOpen = probePort(targetIp, DEFAULT_ESC_POS_PORT, PROBE_TIMEOUT_MS);
                            if (isOpen) {
                                synchronized (discoveredList) {
                                    if (!discoveredList.contains(targetIp)) {
                                        discoveredList.add(targetIp);
                                    }
                                }
                                mainHandler.post(() -> {
                                    if (callback != null && isScanning.get()) {
                                        callback.onPrinterDiscovered(targetIp, DEFAULT_ESC_POS_PORT, "Sunmi Cloud Printer (" + targetIp + ")");
                                    }
                                });
                            }

                            int done = completedCount.incrementAndGet();
                            if (done % 5 == 0 || done == totalTargets) {
                                mainHandler.post(() -> {
                                    if (callback != null && isScanning.get()) {
                                        callback.onScanProgress(done, totalTargets, targetIp);
                                    }
                                });
                            }

                            if (done >= totalTargets) {
                                isScanning.set(false);
                                mainHandler.post(() -> {
                                    if (callback != null) {
                                        callback.onScanCompleted(new ArrayList<>(discoveredList));
                                    }
                                });
                            }
                        });
                    } catch (Exception ignored) {
                        break;
                    }
                }
            } catch (Throwable t) {
                isScanning.set(false);
            }
        }).start();
    }

    private static boolean probePort(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
