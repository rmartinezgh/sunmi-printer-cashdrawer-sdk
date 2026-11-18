# Sunmi Cash Drawer & Cloud Printer SDK — Technical Reference Manual

**Author**: Google DeepMind Agentic Engineering  
**Target Hardware**: Sunmi cPad (Android 13 POS Terminal), Sunmi NT311 Cloud Receipt Printer, Standard 24V RJ12 Cash Drawers  
**Target Platform**: Android 13 (API Level 33, Tiramisu), Java 8+  
**References**:
- [Sunmi Cloud Printer API Documentation](https://docs.sunmi.com/en-US/cdixeghjk491/xffzeghjk557)
- [Sunmi Cash Drawer Control API](https://docs.sunmi.com/en-US/cdixeghjk491/xfxdeghjk524)
- Official Sunmi SDK: `com.sunmi.externalprinterlibrary2` (`EscUtil`, `TransTask`, `StatusTask`)

---

## 1. System Architecture

The Cash Drawer integration system consists of two primary operational paths:
1. **Local Bluetooth RFCOMM Path (Zero-Latency Offline Mode)**: The Sunmi cPad tablet communicates directly with the Sunmi NT311 printer via Bluetooth Serial Port Profile (SPP), allowing high-frequency real-time polling and sub-millisecond solenoid firing without dependency on cloud connectivity.
2. **Sunmi Cloud OpenAPI Path (Remote Management Mode)**: The POS backend or cloud service interacts with Sunmi's Cloud Platform via REST APIs to dispatch remote print/kick tasks and receive asynchronous status callbacks over HMAC-SHA256 authenticated webhooks.

```mermaid
flowchart TD
    subgraph POS_Station["POS Workstation (Front Counter)"]
        CPad["Sunmi cPad (Android 13 Tablet)\n- Sunmi Cash Drawer Controller\n- Hardware Telemetry & Visualizer\n- Audit Report & Watchdog Engine"]
        NT311["Sunmi NT311 Thermal Cloud Printer\n- ESC/POS Firmware Interpreter\n- RJ12 Solenoid Driver (Pin 2)\n- Microswitch Sensor Reader (Pin 3)"]
        Drawer["Cash Drawer\n- 24V Solenoid Release Latch\n- Pin 3 Microswitch (N.C. / N.O.)"]
    end

    subgraph Cloud_Infrastructure["Sunmi Cloud & Backend"]
        SunmiCloud["Sunmi Cloud OpenAPI\nhttps://openapi.sunmi.com"]
        MerchantServer["Merchant POS Backend\n/ Webhook Receiver"]
    end

    CPad -- "Bluetooth SPP (RFCOMM Channel 1)\nUUID: 00001101-...-00805F9B34FB\n[1B 70 00 C8 C8 / 10 04 01]" --> NT311
    NT311 -- "RJ12 Cable\nPin 2 (24V Kick Pulse)\nPin 3 (Microswitch Sensing)" --> Drawer
    Drawer -- "Contact Closure State" --> NT311
    NT311 -- "Wi-Fi / Ethernet MQTT" --> SunmiCloud
    SunmiCloud -- "HTTP POST Webhooks (Port 8080)\nHMAC-SHA256 Signed" --> CPad
    SunmiCloud -- "Webhook Callbacks" --> MerchantServer
```

---

## 2. Hardware Interface & Electrical Characteristics

### 2.1 RJ12 Connector Pinout
The Sunmi NT311 printer features a standard 6P6C RJ12 female receptacle. The pin assignments follow industry POS specifications:

| Pin # | Signal Name | Direction | Electrical Specifications | Description |
|:---:|:---|:---:|:---|:---|
| **1** | Frame GND | — | Chassis Earth Ground | Protective shield against ESD |
| **2** | Drawer 1 Drive | Output | Open-Collector Pull-Down, 24V DC, up to 1.0A | Sinks current through solenoid coil to trigger Drawer 1 |
| **3** | Sensor Status | Input | TTL Logic Level (0V / 3.3V–5V with internal pull-up) | Reads microswitch state in cash drawer chassis |
| **4** | +24V DC | Output | +24V DC ±10% Power Supply | Supplies driving power to solenoid latch |
| **5** | Drawer 2 Drive | Output | Open-Collector Pull-Down, 24V DC | Optional secondary cash drawer trigger pulse |
| **6** | Signal GND | — | 0V Reference | Common return ground for sensor logic |

---

### 2.2 Microswitch Electrical Mechanics (N.C. vs N.O.)

A mechanical microswitch inside the cash drawer housing detects whether the drawer is mechanically seated:

```
  CLOSED DRAWER (Secured)                  OPEN DRAWER (In Transaction)
  ┌─────────────────────────┐              ┌─────────────────────────┐
  │ [=== Cash Drawer ===]   │              │          [=== Cash Drawer ===]
  │           │             │              │                     │
  │           ▼             │              │                     ▼
  │    ( ) Microswitch      │              │              ( ) Microswitch
  │     │   (Pressed)       │              │               │   (Released)
  │    [=] Contact CLOSED   │              │              /    Contact OPEN
  └─────────────────────────┘              └─────────────────────────┘
```

#### Detailed Polarity Matrix:

1. **Normally Closed (N.C.) Microswitch (Default for 90%+ POS Drawers)**:
   - **Drawer Closed**: The physical rear wall of the drawer presses the switch arm, making electrical contact. Pin 3 is connected to the printer's pull-up circuitry and reads **HIGH**. In the printer's status register, **Bit 2 = 1**.
   - **Drawer Open**: The drawer slides forward, releasing the switch arm. The contact breaks, Pin 3 drops to **LOW**. In the printer's status register, **Bit 2 = 0**.
   - **Interpretation**: `Bit 2 == 0` signifies **OPEN**, and `Bit 2 == 1` signifies **CLOSED**.

2. **Normally Open (N.O.) Microswitch**:
   - **Drawer Closed**: Contact is open; Pin 3 reads **LOW** (`Bit 2 = 0`).
   - **Drawer Open**: Contact is closed; Pin 3 reads **HIGH** (`Bit 2 = 1`).
   - **Interpretation**: `Bit 2 == 1` signifies **OPEN**, and `Bit 2 == 0` signifies **CLOSED**.

#### Driver Resolution:
The SDK provides a dynamic inversion flag `invertPolarity = true` (defaulting to N.C. configuration), along with an interactive UI toggle button to guarantee instant hardware compatibility across all drawer brands.

---

## 3. Official Sunmi ESC/POS Command Bytecodes

All bytecodes implemented in this SDK are aligned with the official Sunmi `externalprinterlibrary2` SDK (`com.sunmi.externalprinterlibrary2.utils.EscUtil`):

```java
// Sunmi Official Bytecodes
public static final byte[] CMD_OPEN_CASH_SUNMI = new byte[] { 0x1B, 0x70, 0x00, (byte) 0xC8, (byte) 0xC8 }; // 1b7000c8c8
public static final byte[] CMD_OPEN_CASH_LEGACY = new byte[] { 0x1B, 0x70, 0x00, 0x19, (byte) 0xFA };        // 1b700019fa
public static final byte[] CMD_REAL_TIME = new byte[] { 0x10, 0x04, 0x01 };                                   // 100401
public static final byte[] CMD_CUSTOM_STATUS = new byte[] { 0x1D, 0x28, 0x54, 0x01, 0x00, 0x01 };             // 1d2854010001
public static final byte[] CMD_CUSTOM_STATUS_STABLE = new byte[] { 0x1D, 0x28, 0x54, 0x01, 0x00, 0x09 };      // 1d2854010009
public static final byte[] CMD_ASB_ENABLE = new byte[] { 0x1D, 0x61, 0x01 };                                  // 1d6101
```

### 3.1 Cash Drawer Kick (`ESC p m t1 t2`)
- **Bytecode**: `1B 70 00 C8 C8`
- **Specification**:
  - `0x1B 0x70`: `ESC p` command header.
  - `0x00`: Connector Pin 2 (Drawer 1).
  - `0xC8`: $t_1 = 200$, generating an on-time pulse of $200 \times 2\text{ ms} = 400\text{ ms}$.
  - `0xC8`: $t_2 = 200$, enforcing an off-time delay of $200 \times 2\text{ ms} = 400\text{ ms}$ before the next pulse can fire, protecting the 24V solenoid coil from overheating.

### 3.2 Real-Time Status Transmission (`DLE EOT n`)
- **Bytecode**: `10 04 01`
- **Specification**:
  - Sent out-of-band; executes immediately even if the print buffer is full.
  - The printer firmware reads the immediate voltage level on Pin 3 and transmits the status byte.

### 3.3 Packet Decoding Specifications:
The driver processes both raw 1-byte responses and Sunmi 4-byte encapsulated responses:

```java
int read = inputStream.read(buffer);
final byte statusByte;
if (read == 1) {
    statusByte = buffer[0];
} else if (read >= 4 && (buffer[0] & 0xFF) == 0x37 && (buffer[1] & 0xFF) == 0x09) {
    // Official Sunmi StatusTask multi-byte packet header: 0x37 0x09
    statusByte = buffer[2];
} else {
    statusByte = buffer[0];
}

// Bit 2 Extraction:
int bit2 = (statusByte >> 2) & 0x01;
boolean isOpen = invertPolarity ? (bit2 == 0) : (bit2 == 1);
```

#### Status Byte Bit Allocation:
- **Bit 0**: Fixed (`0`)
- **Bit 1**: Fixed (`1`)
- **Bit 2**: **Cash Drawer Sensor on Pin 3**
- **Bit 3**: Online / Offline indicator
- **Bit 4**: Fixed (`1`)
- **Bit 5**: Platen cover switch (`0` = Closed, `1` = Open)
- **Bit 6**: Paper roll sensor (`0` = Paper present, `1` = Out of paper)
- **Bit 7**: Fixed (`0`)

---

## 4. Official Sunmi Cash Drawer API Specification

Reference: [Sunmi Cash Drawer Control API](https://docs.sunmi.com/en-US/cdixeghjk491/xfxdeghjk524)

### Interface Definition:
```java
public interface SunmiCashDrawerApi {
    /**
     * Send electrical pulse to kick the cash drawer latch open.
     * @param callback Callback notifying caller of execution result.
     */
    void openDrawer(ICallback callback);

    /**
     * Retrieve the cumulative number of times the cash drawer was opened.
     * @return Cumulative open count.
     */
    int getOpenDrawerTimes();

    /**
     * Query the real-time physical sensor status of the cash drawer.
     * @return 0 for CLOSED (Secured), 1 for OPEN (In Transaction), -1 if unavailable.
     */
    int getDrawerStatus();
}
```

### Callback Definition (`ICallback.java`):
```java
public interface ICallback {
    void onRunResult(boolean isSuccess);
    void onReturnString(String result);
    void onRaiseException(int code, String msg);
    void onPrintResult(int code, String msg);
}
```

---

## 5. Cash Drawer Logs & Audit Reporting System

The audit reporting engine (`CashDrawerReportGenerator.java`) aggregates telemetry logs in real time to generate executive audit reports and thermal receipts.

### 5.1 Metrics Calculated
1. **Total Accumulated Opens**: Total number of open events recorded during the active shift.
2. **Open Breakdown**:
   - `BT_KICK_SENT`: Opens triggered by local POS Bluetooth solenoid pulse.
   - `CLOUD_KICK_SENT`: Opens triggered by Sunmi Cloud OpenAPI.
   - `MANUAL_OPEN`: Opens triggered by cashier physical key rotation.
3. **Total Closes**: Total number of close events recorded.
4. **Open Duration Statistics**:
   - $\text{Total Open Time} = \sum (t_{\text{close}} - t_{\text{open}})$
   - $\text{Average Open Duration} = \frac{\text{Total Open Time}}{\text{Completed Cycles}}$
   - $\text{Max Open Duration} = \max(t_{\text{close}} - t_{\text{open}})$
5. **Security & Watchdog Violations**:
   - **Overtime Alerts**: Drawer unclosed past 30 seconds.
   - **Tamper Alerts**: Unauthorized drawer pull or microswitch anomaly.

### 5.2 Direct ESC/POS Thermal Printing Output
When the cashier or manager taps **`🖨️ Print NT311`**, the application constructs an ESC/POS byte sequence:

```
[ESC @]                (Reset & initialize printer)
[ESC a 1] [ESC ! 0x18] (Center align, double height, bold)
CASH DRAWER REPORT
[ESC ! 0x00]           (Standard font)
Sunmi cPad & NT311 Controller
================================
[ESC a 0]              (Left align)
Time:   2026-11-18 23:00:15
SN:     N304T12345678
State:  CLOSED (Secured)
Switch: RJ12 Pin 3 (N.C.)
--------------------------------
[ESC E 1]              (Bold on)
DRAWER USAGE METRICS:
[ESC E 0]              (Bold off)
Total Opens:     14
 - BT Kicks:     10
 - Cloud Kicks:  2
 - Manual Opens: 2
Total Closes:    14
Avg Open Time:   4.2s
Max Open Time:   12.8s
--------------------------------
[ESC E 1]
SECURITY AUDIT:
[ESC E 0]
Overtime Alerts: 0
Tamper Alerts:   0
Status:          VERIFIED SECURE
================================
RECENT AUDIT EVENTS:
[22:45:10] Bluetooth Connected to NT311
[22:45:15] Cash Drawer Opened via Solenoid Pulse
[22:45:20] Cash Drawer Closed & Secured (5.0s)
...
================================
[ESC a 1]
--- END OF AUDIT REPORT ---
[LF LF LF]
[GS V 1]               (Auto paper cut)
```

---

## 6. Cloud OpenAPI & HMAC-SHA256 Webhook Security

### 6.1 Authentication Header Calculation
Every request to Sunmi OpenAPI (`https://openapi.sunmi.com`) must carry HMAC-SHA256 authorization:

```
Signature String = body + Sunmi-Appid + Sunmi-Timestamp + Sunmi-Nonce
Sunmi-Sign       = HMAC-SHA256(Signature String, Sunmi-AppKey).toLowerCaseHex()
```

### 6.2 Embedded Webhook Server (Port 8080)
The Android application includes an embedded HTTP server running on `http://127.0.0.1:8080/sunmi/callback`:
* Validates `Sunmi-Sign` signatures before parsing payloads.
* Emits live events (`CASHDRAWER_STATUS_CHANGED`, `CASHDRAWER_ALARM`) directly into the local audit trail.

---

## 7. Android 13 Build, Packaging, and Deployment

### 7.1 Key Configuration Files
* **Manifest (`app/src/main/AndroidManifest.xml`)**:
  ```xml
  <uses-permission android:name="android.permission.BLUETOOTH" />
  <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
  <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
  <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.VIBRATE" />
  ```
* **Production Keystore Configuration**:
  * **File**: `production.keystore`
  * **Alias**: `sunmi_cashdrawer_prod`
  * **Storepass / Keypass**: `sunmi_prod_2026`
  * **Signature Schemes**: v1 (JAR signing), v2 (Full APK signature), v3 (Android 9+ signature)

### 7.2 Automated Build Pipeline (`build_production_apk.bat`)
The automated build pipeline runs 6 sequential stages:
1. `aapt2 compile`: Compiles Android resources into `build/compiled_res/res.zip`.
2. `aapt2 link`: Packages resources with `android-33/android.jar` and generates `R.java`.
3. `javac`: Compiles Java sources with optimization flags (`-O`).
4. `d8`: Transforms `.class` files into Android bytecode `classes.dex` (`--release --min-api 21`).
5. `zipalign`: Aligns uncompressed data on 4-byte boundaries.
6. `apksigner`: Signs the APK with `production.keystore` supporting v1, v2, and v3 schemes.

### 7.3 Installation Command:
```bash
adb install -r "c:\Users\Lenovo\Documents\antigravity_projects\sunmi_printer_cashdrawer_sdk\app\build\outputs\apk\release\app-release.apk"
```

---

## 8. Troubleshooting & Frequently Asked Questions (FAQ)

### Q1: The cash drawer status is reversed (showing Open when closed and Closed when open).
* **Cause**: Your cash drawer uses a Normally Closed (N.C.) microswitch, but the driver was reading it as Normally Open (N.O.).
* **Resolution**: Tap the **`🔄 Polarity`** button on the main dashboard to switch to `🔄 Polarity: INVERTED (N.C. 0=Open, 1=Closed)`. The app immediately updates the display and logs the transition.

### Q2: Tapping "Kick Cash Drawer" does not pop the drawer open.
* **Checklist**:
  1. Ensure the cash drawer cable is plugged firmly into the **RJ12 port** of the NT311 (not the serial/RS232 port).
  2. Verify your cash drawer is a **24V model** compatible with the NT311 (some 12V models fail to activate or overload the circuit).
  3. Ensure the physical key is in the **Vertical (Unlocked)** position. A horizontally locked drawer mechanically locks the solenoid plunger.

### Q3: How do I pair the NT311 with the Sunmi cPad?
1. On the Sunmi cPad, open **Android Settings -> Bluetooth**.
2. Put the NT311 in discoverable mode or locate its broadcast name (e.g., `NT311-xxxx` or `Printer-xxxx`).
3. Tap to pair. Default pairing PIN is usually `0000` or `1234`.
4. Return to the Cash Drawer app and tap **`📶 Connect NT311 (Bluetooth)`**. Select the paired printer from the dialog list.

### Q4: Can I test the watchdog and alarms without physical hardware?
* **Yes!** The application includes a built-in hardware simulator:
  * Tap **`👐 Manual Open`** to simulate a physical drawer opening.
  * Watch the **Open Timer** count up in real time.
  * After 30 seconds, the **Overtime Watchdog Alarm** activates with an alert banner and vibration.
  * Tap **`🔒 Close Drawer`** to resolve the alarm and record the event in the audit report.
