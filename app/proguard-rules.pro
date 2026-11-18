# Production ProGuard / R8 Rules for Sunmi CashDrawer Simulator

-keep public class com.sunmi.cashdrawer.simulator.MainActivity {
    public *;
}

-keep public class com.sunmi.cashdrawer.simulator.CashDrawerHardwareSimulator {
    public *;
}

-keep public class com.sunmi.cashdrawer.simulator.SunmiCloudPrinter {
    public *;
}

-keep public class com.sunmi.cashdrawer.simulator.SunmiWebhookServer {
    public *;
}

-keep class com.sunmi.cashdrawer.simulator.DrawerEvent {
    public *;
}

-keep class com.sunmi.cashdrawer.simulator.LogAdapter {
    public *;
}

# Preserve line numbers for production stacktraces
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
