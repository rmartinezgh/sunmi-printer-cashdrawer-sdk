package com.sunmi.cashdrawer.simulator;

import android.os.Handler;
import android.os.Looper;

/**
 * Simulates physical Cash Drawer hardware connected to Sunmi Cloud Printer via RJ12 port:
 * Implements the official Sunmi Cash Drawer Control API:
 * https://docs.sunmi.com/en-US/cdixeghjk491/xfxdeghjk524
 * - openDrawer(ICallback callback)
 * - getOpenDrawerTimes()
 * - getDrawerStatus()
 */
public class CashDrawerHardwareSimulator implements SunmiCashDrawerApi {

    public interface HardwareListener {
        void onStateChanged(boolean isOpen, String triggerSource, int openCount);
        void onTimerTick(long elapsedSeconds);
        void onAlert(String alertType, String message);
        void onAlertCleared();
        void onSolenoidPulse(int pin, int onTimeMs, int offTimeMs);
    }

    private boolean isOpen = false;
    private int openCount = 0;
    private long openStartTime = 0;
    private boolean isAlarmActive = false;
    private int timeoutThresholdSec = 30; // 30s timeout alarm threshold

    private final Handler handler = new Handler(Looper.getMainLooper());
    private HardwareListener listener;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isOpen) {
                long elapsedSeconds = (System.currentTimeMillis() - openStartTime) / 1000;
                if (listener != null) {
                    listener.onTimerTick(elapsedSeconds);
                }

                if (elapsedSeconds >= timeoutThresholdSec && !isAlarmActive) {
                    isAlarmActive = true;
                    if (listener != null) {
                        listener.onAlert("TIMEOUT_UNCLOSED",
                                String.format("Cash drawer left open for %d seconds! Alarm triggered.", elapsedSeconds));
                    }
                }
                // Schedule next tick
                handler.postDelayed(this, 1000);
            }
        }
    };

    public void setListener(HardwareListener listener) {
        this.listener = listener;
    }

    public boolean isOpen() {
        return isOpen;
    }

    public boolean isAlarmActive() {
        return isAlarmActive;
    }

    public int getTimeoutThresholdSec() {
        return timeoutThresholdSec;
    }

    public void setTimeoutThresholdSec(int seconds) {
        this.timeoutThresholdSec = Math.max(seconds, 5);
    }

    // ==========================================
    // Sunmi Official API Implementation (xfxdeghjk524)
    // ==========================================

    /**
     * 1. Open the cash drawer via official API
     */
    @Override
    public void openDrawer(final ICallback callback) {
        kickBySolenoid(0, 50, 500);
        if (callback != null) {
            handler.postDelayed(() -> {
                try {
                    callback.onRunResult(true);
                } catch (Exception e) {
                    callback.onRaiseException(-1, e.getMessage());
                }
            }, 50);
        }
    }

    /**
     * 2. Get the number of times the cash drawer has been opened
     */
    @Override
    public int getOpenDrawerTimes() {
        return openCount;
    }

    /**
     * 3. Get cash drawer status (0: Closed, 1: Open)
     */
    @Override
    public int getDrawerStatus() {
        return isOpen ? 1 : 0;
    }

    // ==========================================
    // Hardware Simulation Internals
    // ==========================================

    /**
     * Simulates receiving electrical solenoid pulse from Cloud Printer RJ12 Pin 2
     */
    public void kickBySolenoid(int pin, int onTimeMs, int offTimeMs) {
        if (listener != null) {
            listener.onSolenoidPulse(pin, onTimeMs, offTimeMs);
        }

        // Pulse duration delay simulation
        handler.postDelayed(() -> {
            openInternal("SOLENOID_PULSE");
        }, Math.min(onTimeMs, 100));
    }

    /**
     * Simulates physical key open by cashier
     */
    public void manualOpen() {
        openInternal("MANUAL_KEY");
    }

    private synchronized void openInternal(String source) {
        if (!isOpen) {
            isOpen = true;
            openCount++;
            openStartTime = System.currentTimeMillis();
            isAlarmActive = false;

            if (listener != null) {
                listener.onStateChanged(true, source, openCount);
                listener.onTimerTick(0);
            }

            handler.removeCallbacks(timerRunnable);
            handler.postDelayed(timerRunnable, 1000);
        }
    }

    /**
     * Simulates physically pushing the cash drawer shut
     */
    public synchronized void manualClose() {
        if (isOpen) {
            isOpen = false;
            handler.removeCallbacks(timerRunnable);
            boolean wasAlarm = isAlarmActive;
            isAlarmActive = false;

            if (listener != null) {
                listener.onStateChanged(false, "MANUAL_CLOSE", openCount);
                listener.onTimerTick(0);
                if (wasAlarm) {
                    listener.onAlertCleared();
                }
            }
        }
    }

    /**
     * Manually triggers simulated unclosed alarm for testing
     */
    public void forceTriggerAlarm() {
        if (!isOpen) {
            openInternal("ALARM_TEST");
        }
        isAlarmActive = true;
        if (listener != null) {
            listener.onAlert("TIMEOUT_UNCLOSED", "Simulated overtime alert triggered (>30s)!");
        }
    }

    /**
     * Returns ESC/POS real-time status byte (DLE EOT 1)
     * Bit 2 is drawer pin 3 switch sensor:
     * 1 = Open (0x04)
     * 0 = Closed (0x00)
     */
    public byte getRealtimeStatusByte() {
        int status = 0x00;
        if (isOpen) {
            status |= 0x04; // Bit 2 = 1 (Drawer Open)
        }
        return (byte) status;
    }
}
