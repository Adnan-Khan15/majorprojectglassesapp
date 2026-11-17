// SmartGlasses firmware: button -> camera JPEG + VL53L0X distance -> BLE -> phone,
// which describes it and writes the sentence back on RESULT_TEXT.
// Hardware modules: camera.cpp, tof.cpp, speaker.cpp (I2S1), mic.cpp (I2S0 PDM),
// console.cpp (serial test commands), settings.cpp (NVS). Pins: include/pins.h.
#include <Arduino.h>
#include <NimBLEDevice.h>
#include "camera.h"
#include "console.h"
#include "glasses_protocol.h"
#include "mic.h"
#include "pins.h"
#include "settings.h"
#include "speaker.h"
#include "tof.h"

static constexpr uint32_t DEBOUNCE_MS = 40;
static constexpr uint32_t INDICATION_ACK_TIMEOUT_MS = 2000;
// 244 B = one indication per link-layer packet (251 B with Data Length Extension).
// Full-MTU chunks (512 B) were silently dropped by a phone's Bluetooth stack in
// testing: no ACK, so the link hung until the 30 s ATT timeout. Cause not established.
static constexpr size_t MAX_CHUNK_BYTES = 244;

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

// ============================ Capture flow ============================

static bool buttonPressed() {
    static bool lastStable = LOW, lastRead = LOW;
    static uint32_t changedAt = 0;
    bool r = digitalRead(PIN_BUTTON);
    if (r != lastRead) { lastRead = r; changedAt = millis(); }
    if (millis() - changedAt > DEBOUNCE_MS && r != lastStable) {
        lastStable = r;
        return r == HIGH;  // rising edge = press
    }
    return false;
}

static void captureAndSend() {
    lastPressMs = millis();
    Serial.printf("\n[BTN] tap at %lu ms\n", (unsigned long)lastPressMs);
    digitalWrite(PIN_LED, LED_ON);

    if (!clientConnected || !controlSubscribed || !imageSubscribed) {
        Serial.printf("[BTN] not sent: connected=%d control=%d image=%d\n",
                      clientConnected, controlSubscribed, imageSubscribed);
        digitalWrite(PIN_LED, LED_OFF);
        return;
    }
    camera::Shot shot;
    if (!camera::capture(shot)) {
        Serial.println("[CAM] no photo; nothing to send");
        digitalWrite(PIN_LED, LED_OFF);
        return;
    }
    // Protocol v1 carries the distance in the header, so it's measured before sending.
    // (Protocol v2 overlaps it with the image transfer instead.)
    tof_result_t t = tof::measure(settings::get().tofSamples);
    uint16_t distanceMm = (t.flags & TOF_FLAG_VALID) ? t.median_mm : 0;
    uint32_t capturedMs = millis();
    Serial.printf("[CAM] frame %ux%u, %u bytes; [TOF] distance %u mm  (capture+ToF %lu ms)\n",
                  shot.width, shot.height, (unsigned)shot.len, distanceMm,
                  (unsigned long)(capturedMs - lastPressMs));

    // CONTROL header: uint32 jpegSize LE, uint16 distanceMm LE (see glasses_protocol.h)
    uint8_t header[CONTROL_HEADER_LEN];
    uint32_t size = shot.len;
    memcpy(header, &size, 4);
    memcpy(header + 4, &distanceMm, 2);
    bool ok = indicateAndWait(controlChar, header, sizeof header);
    Serial.printf("[BLE] CONTROL header sent: size=%lu dist=%u -> %s\n",
                  (unsigned long)size, distanceMm, ok ? "acknowledged" : "FAILED");

    size_t chunk = min((size_t)(peerMtu > 3 ? peerMtu - 3 : 20), MAX_CHUNK_BYTES);  // ATT header is 3 B
    Serial.printf("[BLE] streaming %lu bytes in %u-byte chunks (MTU %u)\n",
                  (unsigned long)size, (unsigned)chunk, (unsigned)peerMtu);
    size_t sent = 0, packets = 0;
    while (ok && sent < shot.len) {
        size_t n = min(chunk, shot.len - sent);
        ok = indicateAndWait(imageChar, shot.jpeg + sent, n);
        if (ok) { sent += n; packets++; }
    }

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
    digitalWrite(PIN_LED, LED_OFF);
}

static void simulateHold() {
    Serial.println("[BTN] hold-to-talk arrives in Phase 3 milestone M6");
}

void setup() {
    Serial.begin(115200);
    delay(1500);  // let USB CDC enumerate so early logs aren't lost
    Serial.printf("\n[SYS] SmartGlasses firmware %s\n", FW_VERSION_STR);
    // Brownouts (e.g. speaker current spikes) show up here as reason 9 (ESP_RST_BROWNOUT).
    Serial.printf("[SYS] reset reason %d (1=power-on, 3=software, 4=panic, 9=brownout)\n", (int)esp_reset_reason());
    settings::load();

    pinMode(PIN_BUTTON, INPUT_PULLDOWN);
    for (int pin : UNUSED_PULLDOWN_PINS) pinMode(pin, INPUT_PULLDOWN);
    pinMode(PIN_LED, OUTPUT);
    digitalWrite(PIN_LED, LED_OFF);

    Serial.println(camera::begin() ? "[CAM] camera OK (VGA JPEG)" : "[CAM] !! camera FAILED - captures disabled");
    tof::begin();     // logs its own result; captures work without it (no distance)
    speaker::begin();
    mic::begin();
    setupBle();
    console::begin(captureAndSend, simulateHold);
    if (speaker::ok()) speaker::earcon(speaker::Earcon::Ready);
}

void loop() {
    if (buttonPressed()) captureAndSend();
    console::poll();
    delay(5);
}
