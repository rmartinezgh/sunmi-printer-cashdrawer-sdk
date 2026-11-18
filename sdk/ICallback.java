package com.sunmi.cloudprinter.sdk;

/**
 * Official Sunmi Cash Drawer Control Callback
 * Reference: https://docs.sunmi.com/en-US/cdixeghjk491/xfxdeghjk524
 */
public interface ICallback {
    void onRunResult(boolean isSuccess);
    void onReturnString(String result);
    void onRaiseException(int code, String msg);
    void onPrintResult(int code, String msg);
}
