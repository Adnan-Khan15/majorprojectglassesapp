// SmartGlasses firmware — capture + transfer.
// Button press -> camera JPEG + VL53L1X distance -> CONTROL header indication
// -> JPEG streamed over IMAGE_DATA indications -> phone describes it and writes
// the sentence back on RESULT_TEXT, which we log (speech is Phase 7).
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Wire.h>
#include <VL53L1X.h>
#include "esp_camera.h"
#include "glasses_protocol.h"

// Pin map matches the existing CameraBLE bring-up sketch:
// push-button drives D0 HIGH when pressed (internal pull-down), LED on D8.
static constexpr int BUTTON_PIN = D0;
static constexpr int LED_PIN = D8;
// Unused header pins are pulled down so they can't float and pick up noise,
// as in the bring-up sketch. D4/D5 are the VL53L1X I2C bus and must NOT be
// pulled down. The I2S speaker pins come off this list in Phase 7.
static constexpr int UNUSED_PINS[] = {D1, D2, D3, D6};
static constexpr uint32_t DEBOUNCE_MS = 40;

// VGA keeps print legible for OCR while staying small enough to send quickly.
// The phone downscales to 512 px for Gemma anyway.
static constexpr framesize_t CAPTURE_SIZE = FRAMESIZE_VGA;
static constexpr int JPEG_QUALITY = 12;  // 0-63, lower = better quality, bigger file
static constexpr uint32_t INDICATION_ACK_TIMEOUT_MS = 2000;
// 244 B = one indication per link-layer packet (251 B with Data Length Extension).
// Full-MTU chunks (512 B) were silently dropped by a phone's Bluetooth stack in
// testing: no ACK, so the link hung until the 30 s ATT timeout.
static constexpr size_t MAX_CHUNK_BYTES = 244;
// A VGA JPEG under this is almost certainly a black/blank frame.
static constexpr size_t SUSPICIOUSLY_SMALL_JPEG = 8000;

// ---- XIAO ESP32S3 Sense onboard camera pin map (from hardware_bringup.ino) ----
#define PWDN_GPIO_NUM   -1
#define RESET_GPIO_NUM  -1
#define XCLK_GPIO_NUM   10
#define SIOD_GPIO_NUM   40
#define SIOC_GPIO_NUM   39
#define Y9_GPIO_NUM     48
#define Y8_GPIO_NUM     11
#define Y7_GPIO_NUM     12
#define Y6_GPIO_NUM     14
#define Y5_GPIO_NUM     16
#define Y4_GPIO_NUM     18
#define Y3_GPIO_NUM     17
#define Y2_GPIO_NUM     15
#define VSYNC_GPIO_NUM  38
#define HREF_GPIO_NUM   47
#define PCLK_GPIO_NUM   13

static NimBLEServer* server = nullptr;
static NimBLECharacteristic* controlChar = nullptr;
static NimBLECharacteristic* imageChar = nullptr;
static NimBLECharacteristic* resultChar = nullptr;

static volatile bool clientConnected = false;
static volatile bool controlSubscribed = false;
static volatile bool imageSubscribed = false;
static volatile uint16_t peerMtu = 23;
static volatile uint16_t connHandle = BLE_HS_CONN_HANDLE_NONE;

// One indication in flight at a time: send, then wait for the phone's ACK.
static SemaphoreHandle_t indicationDone = nullptr;
static volatile int indicationStatus = 0;

static VL53L1X tof;
static bool cameraOk = false;
static bool tofOk = false;
static uint32_t lastPressMs = 0;

static void startAdvertising();

// ============================ BLE ============================

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
        clientConnected = true;
        connHandle = info.getConnHandle();
        Serial.printf("[BLE] client connected: %s\n", info.getAddress().toString().c_str());
        // 7.5–15 ms interval for throughput; supervision timeout 4 s.
        s->updateConnParams(info.getConnHandle(), 6, 12, 0, 400);
    }
    void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int reason) override {
        clientConnected = false;
        connHandle = BLE_HS_CONN_HANDLE_NONE;
        controlSubscribed = false;
        imageSubscribed = false;
        peerMtu = 23;
        xSemaphoreGive(indicationDone);  // unblock a transfer that was waiting on an ACK
        Serial.printf("[BLE] client disconnected (reason 0x%02x)\n", reason);
        startAdvertising();
    }
    void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override {
        peerMtu = mtu;
        Serial.printf("[BLE] MTU negotiated: %u\n", mtu);
    }
};

class IndicateCallbacks : public NimBLECharacteristicCallbacks {
    // Fires when the phone ACKs (BLE_HS_EDONE) or fails to ACK an indication.
    void onStatus(NimBLECharacteristic*, int code) override {
        indicationStatus = code;
        xSemaphoreGive(indicationDone);
    }
    void onSubscribe(NimBLECharacteristic* c, NimBLEConnInfo&, uint16_t subValue) override {
        bool on = subValue != 0;
        if (c == controlChar) controlSubscribed = on; else imageSubscribed = on;
        Serial.printf("[BLE] %s subscription: %u\n", c == controlChar ? "CONTROL" : "IMAGE_DATA", subValue);
    }
};

class ResultCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
        std::string text = c->getValue();
        Serial.printf("[BLE] RESULT_TEXT received (%u bytes): \"%s\"\n", (unsigned)text.size(), text.c_str());
        Serial.printf("[TIME] button press -> RESULT_TEXT back on board: %lu ms\n",
                      (unsigned long)(millis() - lastPressMs));
        // TODO(Phase 7): speak `text` here with SAM -> ESP8266Audio AudioOutputI2S ->
        // MAX98357A. Deferred until the amp and speaker are physically wired to the
        // board; until then the phone speaks the sentence itself (PhoneTtsAnnouncer).
    }
};

static void startAdvertising() {
    bool ok = NimBLEDevice::startAdvertising();
    Serial.printf("[BLE] advertising started as \"%s\": %s\n", GLASSES_DEVICE_NAME, ok ? "ok" : "FAILED");
}

static void setupBle() {
    indicationDone = xSemaphoreCreateBinary();
    NimBLEDevice::init(GLASSES_DEVICE_NAME);
    NimBLEDevice::setPower(9);  // dBm
    NimBLEDevice::setMTU(GLASSES_MAX_MTU);

    server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    auto* indicateCallbacks = new IndicateCallbacks();
    NimBLEService* svc = server->createService(SERVICE_UUID);
    controlChar = svc->createCharacteristic(CONTROL_UUID, NIMBLE_PROPERTY::INDICATE);
    controlChar->setCallbacks(indicateCallbacks);
    imageChar = svc->createCharacteristic(IMAGE_DATA_UUID, NIMBLE_PROPERTY::INDICATE);
    imageChar->setCallbacks(indicateCallbacks);
    resultChar = svc->createCharacteristic(
        RESULT_TEXT_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    resultChar->setCallbacks(new ResultCallbacks());
    svc->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->setName(GLASSES_DEVICE_NAME);
    adv->addServiceUUID(SERVICE_UUID);
    adv->enableScanResponse(true);
    startAdvertising();
}

/** Sends one indication and blocks until the phone acknowledges it. */
static bool indicateAndWait(NimBLECharacteristic* c, const uint8_t* data, size_t len) {
    xSemaphoreTake(indicationDone, 0);  // clear any stale signal
    if (!clientConnected) return false;
    if (!c->indicate(data, len)) {
        Serial.printf("[BLE] indicate(%u B) refused by the BLE stack\n", (unsigned)len);
        return false;
    }
    if (xSemaphoreTake(indicationDone, pdMS_TO_TICKS(INDICATION_ACK_TIMEOUT_MS)) != pdTRUE) {
        Serial.printf("[BLE] no ACK from phone for a %u B indication within %lu ms\n",
                      (unsigned)len, (unsigned long)INDICATION_ACK_TIMEOUT_MS);
        return false;
    }
    if (indicationStatus != BLE_HS_EDONE) {
        Serial.printf("[BLE] %u B indication failed, status %d\n", (unsigned)len, indicationStatus);
        return false;
    }
    return clientConnected;
}

/**
 * After an unacknowledged indication the stack won't send another one until the
 * 30 s ATT timeout expires. Dropping the link instead lets the phone reconnect
 * within a few seconds.
 */
static void resetLinkAfterStuckTransfer() {
    if (clientConnected && connHandle != BLE_HS_CONN_HANDLE_NONE) {
        Serial.println("[BLE] resetting the connection so the phone can reconnect quickly");
        server->disconnect(connHandle);
    }
}

// ======================= Camera + ToF (from hardware_bringup.ino) =======================

static bool initCamera() {
    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer   = LEDC_TIMER_0;
    config.pin_d0 = Y2_GPIO_NUM;  config.pin_d1 = Y3_GPIO_NUM;
    config.pin_d2 = Y4_GPIO_NUM;  config.pin_d3 = Y5_GPIO_NUM;
    config.pin_d4 = Y6_GPIO_NUM;  config.pin_d5 = Y7_GPIO_NUM;
    config.pin_d6 = Y8_GPIO_NUM;  config.pin_d7 = Y9_GPIO_NUM;
    config.pin_xclk  = XCLK_GPIO_NUM;
    config.pin_pclk  = PCLK_GPIO_NUM;
    config.pin_vsync = VSYNC_GPIO_NUM;
    config.pin_href  = HREF_GPIO_NUM;
    config.pin_sccb_sda = SIOD_GPIO_NUM;
    config.pin_sccb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn  = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;
    config.xclk_freq_hz = 20000000;
    config.pixel_format = PIXFORMAT_JPEG;
    config.frame_size   = CAPTURE_SIZE;
    config.jpeg_quality = JPEG_QUALITY;
    config.fb_location  = CAMERA_FB_IN_PSRAM;
    // Two buffers + GRAB_LATEST: the sensor keeps capturing, so a button press
    // gets a frame from "now" rather than whenever the last buffer was filled.
    config.fb_count  = 2;
    config.grab_mode = CAMERA_GRAB_LATEST;

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        Serial.printf("[CAM] init failed, error 0x%x\n", err);
        return false;
    }
    // Camera is mounted upside down on the glasses (as in the CameraBLE sketch).
    sensor_t* s = esp_camera_sensor_get();
    s->set_vflip(s, 1);
    s->set_hmirror(s, 1);
    return true;
}

static bool initTof() {
    Wire.begin();  // XIAO default I2C: SDA = D4, SCL = D5
    Wire.setClock(400000);
    tof.setTimeout(500);
    if (!tof.init()) return false;
    tof.setDistanceMode(VL53L1X::Long);     // up to ~4 m, forward-facing cone
    tof.setMeasurementTimingBudget(50000);  // 50 ms budget
    tof.startContinuous(50);                // sample every 50 ms
    return true;
}

/** Latest distance in mm, or 0 when there is no valid reading (out of range / sensor fault). */
static uint16_t readDistanceMm() {
    if (!tofOk) return 0;
    uint16_t mm = tof.read();  // waits for the next continuous sample (<= 50 ms)
    if (tof.timeoutOccurred()) {
        Serial.println("[TOF] timeout");
        return 0;
    }
    if (tof.ranging_data.range_status != VL53L1X::RangeValid) {
        Serial.printf("[TOF] no valid reading (%s)\n", VL53L1X::rangeStatusToString(tof.ranging_data.range_status));
        return 0;
    }
    return mm;
}

// ============================ Capture flow ============================

static bool buttonPressed() {
    static bool lastStable = LOW, lastRead = LOW;
    static uint32_t changedAt = 0;
    bool r = digitalRead(BUTTON_PIN);
    if (r != lastRead) { lastRead = r; changedAt = millis(); }
    if (millis() - changedAt > DEBOUNCE_MS && r != lastStable) {
        lastStable = r;
        return r == HIGH;  // rising edge = press
    }
    return false;
}

static void captureAndSend() {
    lastPressMs = millis();
    Serial.printf("\n[BTN] button pressed at %lu ms\n", (unsigned long)lastPressMs);
    digitalWrite(LED_PIN, HIGH);

    if (!clientConnected || !controlSubscribed || !imageSubscribed) {
        Serial.printf("[BTN] not sent: connected=%d control=%d image=%d\n",
                      clientConnected, controlSubscribed, imageSubscribed);
        digitalWrite(LED_PIN, LOW);
        return;
    }
    if (!cameraOk) {
        Serial.println("[CAM] camera not initialised; nothing to send");
        digitalWrite(LED_PIN, LOW);
        return;
    }

    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) {
        Serial.println("[CAM] capture failed");
        digitalWrite(LED_PIN, LOW);
        return;
    }
    uint16_t distanceMm = readDistanceMm();
    uint32_t capturedMs = millis();
    Serial.printf("[CAM] frame %ux%u, %u bytes; [TOF] distance %u mm  (capture+ToF %lu ms)\n",
                  fb->width, fb->height, (unsigned)fb->len, distanceMm,
                  (unsigned long)(capturedMs - lastPressMs));
    if (fb->len < SUSPICIOUSLY_SMALL_JPEG) {
        Serial.println("[CAM] warning: very small JPEG - lens covered, too dark, or camera not ready?");
    }

    // CONTROL header: uint32 jpegSize LE, uint16 distanceMm LE (see glasses_protocol.h)
    uint8_t header[CONTROL_HEADER_LEN];
    uint32_t size = fb->len;
    memcpy(header, &size, 4);
    memcpy(header + 4, &distanceMm, 2);
    bool ok = indicateAndWait(controlChar, header, sizeof header);
    Serial.printf("[BLE] CONTROL header sent: size=%lu dist=%u -> %s\n",
                  (unsigned long)size, distanceMm, ok ? "acknowledged" : "FAILED");

    size_t chunk = min((size_t)(peerMtu > 3 ? peerMtu - 3 : 20), MAX_CHUNK_BYTES);  // ATT header is 3 B
    Serial.printf("[BLE] streaming %lu bytes in %u-byte chunks (MTU %u)\n",
                  (unsigned long)size, (unsigned)chunk, (unsigned)peerMtu);
    size_t sent = 0, packets = 0;
    while (ok && sent < fb->len) {
        size_t n = min(chunk, fb->len - sent);
        ok = indicateAndWait(imageChar, fb->buf + sent, n);
        if (ok) { sent += n; packets++; }
    }
    esp_camera_fb_return(fb);

    uint32_t transferMs = millis() - capturedMs;
    if (ok) {
        Serial.printf("[BLE] IMAGE_DATA sent: %u bytes in %u packets of <=%u B, %lu ms (%.1f KB/s)\n",
                      (unsigned)sent, (unsigned)packets, (unsigned)chunk, (unsigned long)transferMs,
                      transferMs ? sent / 1.024 / transferMs : 0.0);
        Serial.println("[BLE] waiting for RESULT_TEXT from phone...");
    } else {
        Serial.printf("[BLE] transfer ABORTED after %u of %lu bytes (%lu ms)\n",
                      (unsigned)sent, (unsigned long)size, (unsigned long)transferMs);
        resetLinkAfterStuckTransfer();
    }
    digitalWrite(LED_PIN, LOW);
}

void setup() {
    Serial.begin(115200);
    delay(1500);  // let USB CDC enumerate so early logs aren't lost
    Serial.println("\n[SYS] SmartGlasses firmware - capture + transfer");
    pinMode(BUTTON_PIN, INPUT_PULLDOWN);
    for (int pin : UNUSED_PINS) pinMode(pin, INPUT_PULLDOWN);
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);

    cameraOk = initCamera();
    Serial.println(cameraOk ? "[CAM] camera OK (VGA JPEG)" : "[CAM] !! camera FAILED - captures disabled");
    tofOk = initTof();
    Serial.println(tofOk ? "[TOF] VL53L1X OK" : "[TOF] !! VL53L1X FAILED - check D4/D5 wiring; distance will be omitted");

    setupBle();
}

void loop() {
    if (buttonPressed()) captureAndSend();
    // Serial 'p' simulates the button for bench testing without hardware wiring.
    if (Serial.available() && Serial.read() == 'p') captureAndSend();
    delay(5);
}
