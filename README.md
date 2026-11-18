# Sunmi Cloud Printer - Cash Drawer Status Detection & Simulator SDK

Reference: [Sunmi Cloud Printer Documentation](https://docs.sunmi.com/en-US/cdixeghjk491/xffzeghjk557)

This repository provides a complete, production-ready implementation for **Cash Drawer control and status detection via Sunmi Cloud Printers**, featuring:
1. **Extended Sunmi Cloud Printer Java SDK (`sdk/SunmiCloudPrinter.java`)**: Full implementation of ESC/POS drawer kick commands, real-time status query, OpenAPI endpoints, HMAC-SHA256 signature verification, and webhook event callback handling.
2. **Android Cash Drawer Simulator App (`app/`)**: A dedicated Android application that visually simulates the cash drawer hardware, RJ12 pin 3 microswitch sensor, solenoid kicker, anti-theft overtime alarm (>30s unclosed), and hosts an embedded webhook server to receive live or simulated Sunmi Cloud callbacks.
3. **Pre-built & Signed APK**: Ready-to-install Android package located at `build/SunmiCashDrawerSimulator.apk`.

---

## 1. Hardware Architecture & RJ12 Pinout

Sunmi Cloud Printers (such as the 58mm/80mm Cloud Receipt & Kitchen Printers, NT310/NT311) connect to standard cash drawers via an **RJ12 modular interface**:

```
           RJ12 6-Pin Connector
         ┌───────────────────────┐
         │  1  2  3  4  5  6     │
         └───┬──┬──┬──┬──┬──┬────┘
             │  │  │  │  │  │
Pin 1: Frame GND
Pin 2: Drawer 1 Solenoid Drive Pulse (-)
Pin 3: Drawer Microswitch Sensor (0: Closed / HIGH, 1: Open / LOW)
Pin 4: +24V DC Power (or +12V on 58mm compact models)
Pin 5: Drawer 2 Solenoid Drive Pulse (-) (Optional secondary drawer)
Pin 6: Signal Ground
```

### Sensor Mechanism (Pin 3)
* **Drawer Closed**: The physical microswitch contacts close, presenting **HIGH (logic level 0 in ESC/POS)** to the printer firmware.
* **Drawer Open**: The drawer slides out and releases the spring microswitch, presenting **LOW (logic level 1 in ESC/POS)**.

---

## 2. ESC/POS Command Set for Cash Drawer

### A. Kick Cash Drawer Pulse (`ESC p m t1 t2`)
Generates an electrical solenoid pulse on Pin 2 or Pin 5 to pop the latch open.

* **ASCII**: `ESC p m t1 t2`
* **Hex Format**: `1B 70 m t1 t2`
* **Parameters**:
  * `m = 0x00`: Connector Pin 2 (Drawer 1)
  * `m = 0x01`: Connector Pin 5 (Drawer 2)
  * `t1`: Solenoid ON duration ($t1 \times 2$ ms). E.g., `0x19` (25) = 50ms.
  * `t2`: Solenoid OFF duration ($t2 \times 2$ ms). E.g., `0xFA` (250) = 500ms.
* **Recommended Default Payload**:
  ```hex
  1B 70 00 19 FA
  ```

### B. Real-Time Status Transmission (`DLE EOT n`)
Requests the printer to immediately return 1 byte of status, bypassing the print queue.

* **ASCII**: `DLE EOT n`
* **Hex Format**: `10 04 01` ($n = 1$: Printer & Drawer status)
* **Status Byte Bitmask**:
  | Bit | Function | Value = 0 | Value = 1 |
  |---|---|---|---|
  | Bit 0 | Fixed | 0 | - |
  | Bit 1 | Fixed | 1 | - |
  | **Bit 2** | **Cash Drawer Status (Pin 3)** | **Closed** | **Open** |
  | Bit 3 | Online / Offline status | Online | Offline |
  | Bit 5 | Cover Status | Closed | Open |
  | Bit 6 | Paper Sensor | Paper Present | Out of Paper |

### C. Automatic Status Back (ASB) (`GS a n`)
Enables automatic transmission of status changes back to the cloud/host whenever the drawer opens or closes.
* **Hex**: `1D 61 01` (Enable drawer status back)

---

## 3. Sunmi Cloud OpenAPI Integration

Sunmi Cloud Printers communicate with the Sunmi Cloud Open Platform (`https://openapi.sunmi.com`).

### A. Authentication & Signatures
Every request to Sunmi OpenAPI requires four headers:
1. `Sunmi-Appid`: Merchant / ISV App ID
2. `Sunmi-Timestamp`: Current UNIX timestamp in seconds
3. `Sunmi-Nonce`: 6-digit random number
4. `Sunmi-Sign`: `HMAC-SHA256(body + Sunmi-Appid + Sunmi-Timestamp + Sunmi-Nonce, Sunmi-AppKey)` (Hex lowercase)

### B. Pushing Drawer Kick via Cloud
Endpoint: `POST /v2/printer/open/open/device/pushContent`

```json
{
  "trade_no": "TR_1716382900",
  "sn": "N403000000000",
  "order_type": 1,
  "content": "1b700019fa",
  "count": 1,
  "media_text": "开钱箱",
  "cycle": 1
}
```

### C. Querying Device Status (Drawer State)
Endpoint: `POST /v2/printer/open/open/device/deviceStatus`

```json
{
  "sn": "N403000000000"
}
```

**Response**:
```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "sn": "N403000000000",
    "online_status": 1,
    "cashdrawer": {
      "status": 1,
      "status_desc": "OPEN",
      "open_count": 42,
      "last_open_time": 1716382900,
      "alarm_state": 0
    }
  }
}
```

### D. Asynchronous Webhook Event Callbacks
When the printer's Pin 3 microswitch state changes, Sunmi Cloud pushes an HTTP POST callback to the partner's registered URL:

```json
{
  "event_type": "CASHDRAWER_STATUS_CHANGED",
  "sn": "N403000000000",
  "timestamp": 1716382905,
  "data": {
    "drawer_status": "OPEN",
    "status_code": 1,
    "open_duration_sec": 0,
    "trigger_source": "CLOUD_COMMAND"
  }
}
```

**Overtime Unclosed Security Alert Callback**:
```json
{
  "event_type": "CASHDRAWER_ALARM",
  "sn": "N403000000000",
  "timestamp": 1716382935,
  "data": {
    "alarm_type": "TIMEOUT_UNCLOSED",
    "alarm_code": 3001,
    "alarm_desc": "Cash drawer has been left open for more than 30 seconds!",
    "open_duration_sec": 30
  }
}
```

---

## 4. Java SDK Usage (`sdk/SunmiCloudPrinter.java`)

```java
import com.sunmi.cloudprinter.sdk.SunmiCloudPrinter;

// 1. Initialize SDK
SunmiCloudPrinter.APP_ID = "YOUR_APP_ID";
SunmiCloudPrinter.APP_KEY = "YOUR_APP_KEY";
SunmiCloudPrinter printer = new SunmiCloudPrinter(384);

// 2. Build print job with Cash Drawer Kick
printer.restoreDefaultSettings();
printer.appendText("Customer Receipt\nTotal: $25.00\n");
printer.lineFeed(2);

// Append Kick Command: ESC p 0 25 250 -> 1b700019fa
printer.openCashDrawer();
printer.cutPaper(false);

// 3. Push to Cloud Printer
String resp = printer.pushContent("ORDER_001", "N403000000000", 1, 1, "Receipt", 1);
System.out.println("Push response: " + resp);

// 4. Query Cash Drawer Status
String statusResp = printer.deviceStatus("N403000000000");
System.out.println("Device status: " + statusResp);

// 5. Parse Real-Time Status Byte (DLE EOT 1)
SunmiCloudPrinter.CashDrawerHardwareState state =
    new SunmiCloudPrinter.CashDrawerHardwareState(0x04);
System.out.println("Is Drawer Open: " + state.isOpen); // true
```

---

## 5. Android Simulation App (`app/`)

The simulation app provides an interactive dashboard:

### Features
1. **Interactive Hardware Visualizer**:
   * Animated cash drawer box sliding open/closed smoothly.
   * Internal bill & coin compartments (`¥100`, `¥50`, `¥20`, `Coins`).
   * Color-coded status badge:
     * 🟢 `CLOSED (SECURED)`
     * 🟡 `OPEN (IN TRANSACTION)`
     * 🔴 `ALERT: UNCLOSED OVERTIME (>30s)`
2. **RJ12 Telemetry Monitor**:
   * **PIN 3 Sensor**: Displays real-time microswitch state (`HIGH (Closed)` / `LOW (Open)`).
   * **PIN 2 Drive**: Flashes `24V PULSE (50ms)` when kick command is received, then returns to `IDLE`.
   * **Open Timer**: Real-time counter of open duration (e.g. `00:15`).
   * **Open Counter**: Cumulative counter tracking how many times the drawer has been triggered.
3. **Anti-Theft Watchdog Alert**:
   * If the drawer remains open past the threshold (default: 30 seconds), a flashing red security banner activates, accompanied by haptic alerts and alarm logs.
4. **Embedded Webhook Server (Port 8080)**:
   * Built-in local HTTP server running on `http://127.0.0.1:8080/sunmi/callback`.
   * Validates `Sunmi-Sign` HMAC-SHA256 signatures on incoming requests.
   * Dispatches events directly to the in-app audit log.
5. **Interactive Action Controls**:
   * **⚡ Kick Drawer (Cloud)**: Generates ESC/POS `1B 70 00 19 FA`, dispatches cloud API request, triggers 24V solenoid pulse, and slides drawer open.
   * **👐 Manual Open**: Simulates cashier physical key open.
   * **🔒 Close Drawer**: Snaps the drawer shut and resets watchdog timers.
   * **🚨 Simulate Alarm**: Tests immediate >30s overtime warning and buzzer.
   * **📡 Sim Cloud Webhook**: Pushes realistic Sunmi Cloud event callbacks to the local webhook server.
   * **🔎 Query OpenAPI**: Queries device and cash drawer status from the cloud platform.
   * **⚙️ Config Dialog**: Configure `APP_ID`, `APP_KEY`, Printer `SN`, and timeout threshold.
   * **Live Audit Log**: Filterable chronological log with timestamp, event tag, raw hex/JSON, details modal, and one-click copy to clipboard.

---

## 6. How to Build & Install the APK

### Instant One-Click Build
Run the included build script:
```bat
build_apk.bat
```

### Pre-built APK Location
The signed APK is ready for deployment:
* `build/SunmiCashDrawerSimulator.apk`
* `app/build/outputs/apk/debug/app-debug.apk`

### Install on Device or Emulator via ADB
```bash
adb install -r build/SunmiCashDrawerSimulator.apk
```
Or launch via ADB:
```bash
adb shell am start -n com.sunmi.cashdrawer.simulator/.MainActivity
```
