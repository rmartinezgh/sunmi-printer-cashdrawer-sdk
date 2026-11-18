package com.sunmi.cashdrawer.simulator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Generates comprehensive Cash Drawer Audit & Telemetry Reports.
 * Provides formatted plain text, CSV, and official ESC/POS binary data
 * for direct thermal printing on Sunmi NT311 via Bluetooth.
 */
public class CashDrawerReportGenerator {

    public static class ReportSummary {
        public int totalEvents = 0;
        public int totalOpens = 0;
        public int totalCloses = 0;
        public int solenoidKicks = 0;
        public int manualOpens = 0;
        public int cloudKicks = 0;
        public int overtimeAlerts = 0;
        public int tamperAlerts = 0;
        public long totalOpenDurationMs = 0;
        public long maxOpenDurationMs = 0;
        public long averageOpenDurationMs = 0;

        public boolean currentIsOpen = false;
        public boolean isInvertedPolarity = true;
        public String printerName = "Sunmi NT311";
        public String serialNumber = SunmiCloudPrinter.DEFAULT_SN;
        public String generatedAt = "";
        public String sessionStart = "";

        public final List<DrawerEvent> recentEvents = new ArrayList<>();
    }

    /**
     * Compute statistics from current events and state
     */
    public static ReportSummary computeSummary(List<DrawerEvent> events,
                                               boolean currentIsOpen,
                                               boolean isInvertedPolarity,
                                               String printerName,
                                               String sn) {
        ReportSummary summary = new ReportSummary();
        summary.currentIsOpen = currentIsOpen;
        summary.isInvertedPolarity = isInvertedPolarity;
        summary.printerName = (printerName != null && !printerName.isEmpty()) ? printerName : "Sunmi NT311";
        summary.serialNumber = (sn != null && !sn.isEmpty()) ? sn : SunmiCloudPrinter.DEFAULT_SN;

        SimpleDateFormat sdfFull = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        summary.generatedAt = sdfFull.format(new Date());

        if (events == null || events.isEmpty()) {
            summary.sessionStart = summary.generatedAt;
            return summary;
        }

        summary.sessionStart = sdfFull.format(new Date(events.get(0).timestamp));
        summary.totalEvents = events.size();

        long lastOpenTimestamp = -1;
        int completedCycles = 0;

        for (DrawerEvent event : events) {
            summary.recentEvents.add(event);

            switch (event.type) {
                case DRAWER_OPENED:
                    summary.totalOpens++;
                    lastOpenTimestamp = event.timestamp;
                    if (event.details != null && event.details.toLowerCase().contains("manual")) {
                        summary.manualOpens++;
                    }
                    break;

                case DRAWER_CLOSED:
                    summary.totalCloses++;
                    if (lastOpenTimestamp > 0) {
                        long duration = event.timestamp - lastOpenTimestamp;
                        if (duration > 0) {
                            summary.totalOpenDurationMs += duration;
                            if (duration > summary.maxOpenDurationMs) {
                                summary.maxOpenDurationMs = duration;
                            }
                            completedCycles++;
                        }
                        lastOpenTimestamp = -1;
                    }
                    break;

                case BT_KICK_SENT:
                    summary.solenoidKicks++;
                    break;

                case CLOUD_KICK_SENT:
                    summary.cloudKicks++;
                    break;

                case ALERT_TIMEOUT:
                    summary.overtimeAlerts++;
                    break;

                case ALERT_TAMPER:
                    summary.tamperAlerts++;
                    break;

                default:
                    break;
            }
        }

        if (completedCycles > 0) {
            summary.averageOpenDurationMs = summary.totalOpenDurationMs / completedCycles;
        }

        return summary;
    }

    /**
     * Generate structured, aligned plain-text report for viewing, copying, or sharing.
     */
    public static String generateTextReport(ReportSummary s) {
        StringBuilder sb = new StringBuilder();
        String line = "==================================================";
        String dashed = "--------------------------------------------------";

        sb.append(line).append("\n");
        sb.append("         SUNMI CASH DRAWER AUDIT REPORT          \n");
        sb.append("          cPad POS + NT311 Cloud Printer         \n");
        sb.append(line).append("\n");
        sb.append(String.format("Generated:    %s\n", s.generatedAt));
        sb.append(String.format("Session From: %s\n", s.sessionStart));
        sb.append(String.format("Printer:      %s\n", s.printerName));
        sb.append(String.format("Device SN:    %s\n", s.serialNumber));
        sb.append(dashed).append("\n");
        sb.append(">> REAL-TIME HARDWARE STATUS\n");
        sb.append(String.format("  Current State:    %s\n", s.currentIsOpen ? "OPEN (IN TRANSACTION)" : "CLOSED (SECURED)"));
        sb.append(String.format("  Sensor Wiring:    RJ12 Pin 3 (%s)\n", s.isInvertedPolarity ? "Normally Closed / N.C." : "Normally Open / N.O."));
        sb.append(String.format("  Active Polarity:  %s\n", s.isInvertedPolarity ? "INVERTED (0=Open, 1=Closed)" : "NORMAL (1=Open, 0=Closed)"));
        sb.append(dashed).append("\n");
        sb.append(">> DRAWER USAGE METRICS\n");
        sb.append(String.format("  Total Opens:      %d\n", s.totalOpens));
        sb.append(String.format("    - BT Kicks:     %d\n", s.solenoidKicks));
        sb.append(String.format("    - Cloud Kicks:  %d\n", s.cloudKicks));
        sb.append(String.format("    - Manual Opens: %d\n", s.manualOpens));
        sb.append(String.format("  Total Closes:     %d\n", s.totalCloses));
        sb.append(dashed).append("\n");
        sb.append(">> TIMING & DURATION ANALYSIS\n");
        sb.append(String.format("  Avg Open Time:    %.1f seconds\n", s.averageOpenDurationMs / 1000.0f));
        sb.append(String.format("  Max Open Time:    %.1f seconds\n", s.maxOpenDurationMs / 1000.0f));
        sb.append(dashed).append("\n");
        sb.append(">> SECURITY & WATCHDOG ALERTS\n");
        sb.append(String.format("  Overtime Alerts:  %d (>30s Unclosed)\n", s.overtimeAlerts));
        sb.append(String.format("  Tamper Alerts:    %d\n", s.tamperAlerts));
        sb.append(String.format("  Security Rating:  %s\n", (s.overtimeAlerts == 0 && s.tamperAlerts == 0) ? "VERIFIED SECURE" : "FLAGGED / AUDIT REQUIRED"));
        sb.append(line).append("\n");
        sb.append(">> AUDIT TRAIL LOG ENTRIES (Last ").append(s.recentEvents.size()).append(" events)\n");
        sb.append(dashed).append("\n");

        for (int i = 0; i < s.recentEvents.size(); i++) {
            DrawerEvent e = s.recentEvents.get(i);
            sb.append(String.format("[%s] %-14s %s\n", e.getFormattedTime(), e.type.tag, e.title));
            if (e.details != null && !e.details.isEmpty()) {
                sb.append(String.format("       Details: %s\n", e.details));
            }
            if (e.rawHexOrJson != null && !e.rawHexOrJson.isEmpty()) {
                sb.append(String.format("       Raw:     %s\n", e.rawHexOrJson));
            }
        }

        sb.append(line).append("\n");
        sb.append("              [END OF AUDIT REPORT]              \n");
        sb.append(line).append("\n");

        return sb.toString();
    }

    /**
     * Generate ESC/POS thermal printer byte array for Sunmi NT311 (58mm/80mm).
     * Includes ESC/POS formatting commands:
     * - ESC @ (Initialize printer)
     * - ESC a 1 (Center align)
     * - ESC ! 0x10 (Double height header)
     * - ESC a 0 (Left align)
     * - Feed & Paper Cut (GS V 1)
     */
    public static byte[] generateEscPosReport(ReportSummary s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // ESC @ - Initialize
            out.write(new byte[]{0x1B, 0x40});

            // Center align
            out.write(new byte[]{0x1B, 0x61, 0x01});
            // Double height & bold for header
            out.write(new byte[]{0x1B, 0x21, 0x18});
            out.write("CASH DRAWER REPORT\n".getBytes("UTF-8"));
            // Standard font
            out.write(new byte[]{0x1B, 0x21, 0x00});
            out.write("Sunmi cPad & NT311 Controller\n".getBytes("UTF-8"));
            out.write("================================\n".getBytes("UTF-8"));

            // Left align for body
            out.write(new byte[]{0x1B, 0x61, 0x00});
            out.write(String.format("Time:   %s\n", s.generatedAt).getBytes("UTF-8"));
            out.write(String.format("SN:     %s\n", s.serialNumber).getBytes("UTF-8"));
            out.write(String.format("State:  %s\n", s.currentIsOpen ? "** OPEN **" : "CLOSED (Secured)").getBytes("UTF-8"));
            out.write(String.format("Switch: RJ12 Pin 3 (%s)\n", s.isInvertedPolarity ? "N.C." : "N.O.").getBytes("UTF-8"));
            out.write("--------------------------------\n".getBytes("UTF-8"));

            // Bold section
            out.write(new byte[]{0x1B, 0x45, 0x01});
            out.write("DRAWER USAGE METRICS:\n".getBytes("UTF-8"));
            out.write(new byte[]{0x1B, 0x45, 0x00});
            out.write(String.format("Total Opens:     %d\n", s.totalOpens).getBytes("UTF-8"));
            out.write(String.format(" - BT Kicks:     %d\n", s.solenoidKicks).getBytes("UTF-8"));
            out.write(String.format(" - Cloud Kicks:  %d\n", s.cloudKicks).getBytes("UTF-8"));
            out.write(String.format(" - Manual Opens: %d\n", s.manualOpens).getBytes("UTF-8"));
            out.write(String.format("Total Closes:    %d\n", s.totalCloses).getBytes("UTF-8"));
            out.write(String.format("Avg Open Time:   %.1fs\n", s.averageOpenDurationMs / 1000.0f).getBytes("UTF-8"));
            out.write(String.format("Max Open Time:   %.1fs\n", s.maxOpenDurationMs / 1000.0f).getBytes("UTF-8"));
            out.write("--------------------------------\n".getBytes("UTF-8"));

            out.write(new byte[]{0x1B, 0x45, 0x01});
            out.write("SECURITY AUDIT:\n".getBytes("UTF-8"));
            out.write(new byte[]{0x1B, 0x45, 0x00});
            out.write(String.format("Overtime Alerts: %d\n", s.overtimeAlerts).getBytes("UTF-8"));
            out.write(String.format("Tamper Alerts:   %d\n", s.tamperAlerts).getBytes("UTF-8"));
            out.write(String.format("Status:          %s\n", (s.overtimeAlerts == 0 && s.tamperAlerts == 0) ? "VERIFIED SECURE" : "AUDIT REQUIRED").getBytes("UTF-8"));
            out.write("================================\n".getBytes("UTF-8"));

            // Recent 10 events
            out.write("RECENT AUDIT EVENTS:\n".getBytes("UTF-8"));
            int startIdx = Math.max(0, s.recentEvents.size() - 10);
            for (int i = startIdx; i < s.recentEvents.size(); i++) {
                DrawerEvent e = s.recentEvents.get(i);
                out.write(String.format("[%s] %s\n", e.getFormattedTime(), e.title).getBytes("UTF-8"));
            }

            out.write("================================\n".getBytes("UTF-8"));
            // Center footer
            out.write(new byte[]{0x1B, 0x61, 0x01});
            out.write("--- END OF AUDIT REPORT ---\n\n\n".getBytes("UTF-8"));

            // Feed 3 lines and cut (GS V 1)
            out.write(new byte[]{0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x31});

            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Generate standard CSV format for export
     */
    public static String generateCsvReport(ReportSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Timestamp,TypeTag,Title,Details,IsAlert,RawData\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        for (DrawerEvent e : s.recentEvents) {
            sb.append("\"").append(sdf.format(new Date(e.timestamp))).append("\",");
            sb.append("\"").append(escapeCsv(e.type.tag)).append("\",");
            sb.append("\"").append(escapeCsv(e.title)).append("\",");
            sb.append("\"").append(escapeCsv(e.details)).append("\",");
            sb.append(e.isAlert ? "true" : "false").append(",");
            sb.append("\"").append(escapeCsv(e.rawHexOrJson)).append("\"\n");
        }
        return sb.toString();
    }

    private static String escapeCsv(String str) {
        if (str == null) return "";
        return str.replace("\"", "\"\"");
    }
}
