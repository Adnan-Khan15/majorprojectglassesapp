// Phase 1 — BLE link only.
// Button press -> CONTROL indication carrying a test string -> phone echoes it
// back on RESULT_TEXT -> we log the round-trip time.
#include <Arduino.h>
#include <NimBLEDevice.h>
#include "glasses_protocol.h"

// Pin map matches the existing CameraBLE bring-up sketch:
// push-button drives D0 HIGH when pressed (internal pull-down), LED on D8.
static constexpr int BUTTON_PIN = D0;
static constexpr int LED_PIN = D8;
// Unused header pins are pulled down so they can't float and pick up noise,
// as in the bring-up sketch. D4/D5 (I2C for the VL53L1X) and the I2S speaker
// pins must come off this list when those parts are wired in later phases.
static constexpr int UNUSED_PINS[] = {D1, D2, D3, D4, D5, D6};
static constexpr uint32_t DEBOUNCE_MS = 40;

static NimBLEServer* server = nullptr;
static NimBLECharacteristic* controlChar = nullptr;
static NimBLECharacteristic* imageChar = nullptr;
static NimBLECharacteristic* resultChar = nullptr;

static volatile bool clientConnected = false;
static volatile bool controlSubscribed = false;
static uint32_t lastPingSentMs = 0;
static uint32_t pingSeq = 0;

static void startAdvertising();

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
        clientConnected = true;
        Serial.printf("[BLE] client connected: %s\n", info.getAddress().toString().c_str());
        // 7.5–15 ms interval for throughput; supervision timeout 4 s.
        s->updateConnParams(info.getConnHandle(), 6, 12, 0, 400);
    }
    void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int reason) override {
        clientConnected = false;
        controlSubscribed = false;
        Serial.printf("[BLE] client disconnected (reason 0x%02x)\n", reason);
        startAdvertising();
    }
    void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override {
        Serial.printf("[BLE] MTU negotiated: %u\n", mtu);
    }
};

class ControlCallbacks : public NimBLECharacteristicCallbacks {
    // Fires when the phone ACKs (or fails to ACK) an indication.
    void onStatus(NimBLECharacteristic*, int code) override {
        Serial.printf("[BLE] CONTROL indication %s (code %d)\n",
                      code == BLE_HS_EDONE ? "acknowledged by phone" : "NOT acknowledged", code);
    }
    void onSubscribe(NimBLECharacteristic*, NimBLEConnInfo&, uint16_t subValue) override {
        controlSubscribed = subValue != 0;
        Serial.printf("[BLE] CONTROL subscription: %u\n", subValue);
    }
};

class ResultCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
        std::string text = c->getValue();
        uint32_t rtt = millis() - lastPingSentMs;
        Serial.printf("[BLE] RESULT_TEXT (%u bytes): \"%s\"  round trip %lu ms\n",
                      (unsigned)text.size(), text.c_str(), (unsigned long)rtt);
    }
};

static void startAdvertising() {
    bool ok = NimBLEDevice::startAdvertising();
    Serial.printf("[BLE] advertising started as \"%s\": %s\n", GLASSES_DEVICE_NAME, ok ? "ok" : "FAILED");
}

static void setupBle() {
    NimBLEDevice::init(GLASSES_DEVICE_NAME);
    NimBLEDevice::setPower(9);  // dBm
    NimBLEDevice::setMTU(GLASSES_MAX_MTU);

    server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    NimBLEService* svc = server->createService(SERVICE_UUID);
    controlChar = svc->createCharacteristic(CONTROL_UUID, NIMBLE_PROPERTY::INDICATE);
    controlChar->setCallbacks(new ControlCallbacks());
    imageChar = svc->createCharacteristic(IMAGE_DATA_UUID, NIMBLE_PROPERTY::INDICATE);
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

static void sendPing() {
    Serial.printf("[BTN] button pressed at %lu ms\n", (unsigned long)millis());
    digitalWrite(LED_PIN, HIGH);  // brief blink: the press was seen
    if (!clientConnected || !controlSubscribed) {
        Serial.printf("[BTN] not sent: connected=%d subscribed=%d\n", clientConnected, controlSubscribed);
        delay(100);
        digitalWrite(LED_PIN, LOW);
        return;
    }
    char msg[48];
    snprintf(msg, sizeof msg, "PING %lu", (unsigned long)++pingSeq);
    lastPingSentMs = millis();
    controlChar->setValue((uint8_t*)msg, strlen(msg));
    bool ok = controlChar->indicate();
    Serial.printf("[BLE] string sent on CONTROL: \"%s\" -> %s\n", msg, ok ? "queued" : "FAILED");
    delay(100);
    digitalWrite(LED_PIN, LOW);
}

void setup() {
    Serial.begin(115200);
    delay(1500);  // let USB CDC enumerate so early logs aren't lost
    Serial.println("\n[SYS] SmartGlasses firmware - Phase 1 (BLE link test)");
    pinMode(BUTTON_PIN, INPUT_PULLDOWN);
    for (int pin : UNUSED_PINS) pinMode(pin, INPUT_PULLDOWN);
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);
    setupBle();
}

void loop() {
    if (buttonPressed()) sendPing();
    // Serial 'p' simulates the button for bench testing without hardware wiring.
    if (Serial.available() && Serial.read() == 'p') sendPing();
    delay(5);
}
