package com.sunmi.cloudprinter.sdk;

/**
 * Sunmi Official Cash Drawer Control API
 * Reference: https://docs.sunmi.com/en-US/cdixeghjk491/xfxdeghjk524
 */
public interface SunmiCashDrawerApi {

    /**
     * 1. Open the Cash Drawer
     * Sends electrical pulse to open the cash drawer.
     * @param callback Result callback handling success, string result, or exception
     */
    void openDrawer(ICallback callback);

    /**
     * 2. Get Cash Drawer Open Count
     * Cumulative total number of times the cash drawer has been opened.
     * @return Cumulative open count
     */
    int getOpenDrawerTimes();

    /**
     * 3. Get Cash Drawer Status
     * Queries physical switch state on RJ12 Pin 3.
     * @return 0: Cash Drawer CLOSED, 1: Cash Drawer OPEN, -1: Unsupported/Disconnected
     */
    int getDrawerStatus();
}
