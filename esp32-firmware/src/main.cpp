// SmartGlasses firmware: button -> camera JPEG + VL53L0X distance -> BLE -> phone,
// which describes it and answers. Protocol: include/glasses_protocol.h and
// lib/glasses_core/src/glasses_frames.h. Pins: include/pins.h.
// Modules: camera, tof, speaker (I2S1), mic (I2S0 PDM), link (BLE), console
// (serial test commands), settings (NVS), throughput (BLE spike).
#include <Arduino.h>
#include "camera.h"
#include "console.h"
#include "glasses_frames.h"
#include "glasses_protocol.h"
#include "ble_link.h"
#include "mic.h"
#include "pins.h"
#include "settings.h"
#include "speaker.h"
#include "throughput.h"
#include "tof.h"

static constexpr uint32_t DEBOUNCE_MS = 40;
static constexpr uint32_t TOF_WAIT_MS = 4000;  // after the image; N x 200 ms budget fits easily

static uint32_t pressedAtMs = 0;
// Set by the phone after connecting (PARAM_IMAGE_MODE); old apps never set it, so photos stay on indications.
static volatile bool imageViaNotify = false;

// ============================ Events ============================

static void sendCaps() {
    const Settings& s = settings::get();
    caps_t c = {};
    c.protocol = PROTOCOL_VERSION;
    c.fw_major = FW_MAJOR; c.fw_minor = FW_MINOR; c.fw_patch = FW_PATCH;
    c.features = (camera::ok() ? FEAT_CAMERA : 0) | (tof::ok() ? FEAT_TOF : 0) | (speaker::ok() ? FEAT_SPEAKER : 0) |
                 (mic::ok() ? FEAT_MIC : 0) | (psramFound() ? FEAT_PSRAM : 0) | FEAT_IMG_NOTIFY | (speaker::ok() ? FEAT_SPEECH_STREAM : 0);
    c.audio_rate = speaker::SAMPLE_RATE;
    c.samples_per_frame = mic::BLOCK_SAMPLES;
    c.codec = AUDIO_CODEC_ADPCM;
    c.tof_type = tof::ok() ? TOF_TYPE_VL53L0X : TOF_TYPE_NONE;
    c.reset_reason = (uint8_t)esp_reset_reason();
    c.volume = s.volume;
    c.ceiling = s.ceilingPct;
    c.hold_ms = s.holdMs;
    c.ring_frames = speaker::RING_FRAMES;          // phone's initial credit window
    c.prebuffer_frames = speaker::PREBUFFER_FRAMES;
    c.tof_samples = s.tofSamples;
    uint8_t b[CAPS_LEN];
    caps_pack(&c, b);
    ble::sendEvent(EVT_CAPS, b, sizeof b);
}

static void sendTof(uint16_t imageId, const tof_result_t& r) {
    uint8_t p[9];
    put_u16(p, imageId);
    put_u16(p + 2, r.median_mm);
    p[4] = r.flags;
    p[5] = r.valid_count;
    p[6] = r.sample_count;
    put_u16(p + 7, r.spread_mm);
    ble::sendEvent(EVT_TOF_RESULT, p, sizeof p);
}

static void sendError(uint8_t code, const char* text) {
    uint8_t p[40];
    p[0] = code;
    size_t n = strnlen(text, sizeof p - 1);
    memcpy(p + 1, text, n);
    ble::sendEvent(EVT_ERROR, p, n + 1);
    Serial.printf("[ERR] %u: %s\n", code, text);
}

// ============================ Capture ============================

/** Photo now, ToF sampled in parallel with the image transfer, then a TOF_RESULT event. */
static void captureAndSend(uint8_t mode) {
    digitalWrite(PIN_LED, LED_ON);
    if (!ble::imagesReady()) {
        Serial.printf("[CAP] not sent: phone not connected/subscribed (connected=%d)\n", ble::connected());
        digitalWrite(PIN_LED, LED_OFF);
        return;
    }
    camera::Shot shot;
    if (!camera::capture(shot)) {
        sendError(ERR_CAMERA, "camera capture failed");
        digitalWrite(PIN_LED, LED_OFF);
        return;
    }
    tof::startAsync(settings::get().tofSamples);

    uint8_t p[11];
    put_u16(p, shot.id);
    put_u32(p + 2, shot.len);
    put_u16(p + 6, (uint16_t)min<uint32_t>(shot.takenAtMs - pressedAtMs, 65535));
    put_u16(p + 8, (uint16_t)min<size_t>(shot.firstLen, 65535));
    p[10] = mode;
    ble::sendEvent(EVT_PHOTO_CAPTURED, p, sizeof p);

    uint8_t flags = (mode == MODE_QUESTION ? IMG_FLAG_QUESTION : 0) | (shot.firstLen ? IMG_FLAG_RETRIED : 0);
    bool ok = ble::sendImage(shot, flags, imageViaNotify);

    tof_result_t r;
    tof::waitAsync(r, TOF_WAIT_MS);
    sendTof(shot.id, r);
    Serial.printf("[CAP] image %u %s; ToF %u mm (flags 0x%02x); press -> done %lu ms\n", shot.id, ok ? "sent" : "NOT sent",
                  r.median_mm, r.flags, (unsigned long)(millis() - pressedAtMs));
    if (!ok && ble::connected()) ble::reset();
    digitalWrite(PIN_LED, LED_OFF);
}

static void resendLast(uint16_t imageId) {
    const camera::Shot& s = camera::last();
    if (!s.jpeg || s.id != imageId) { sendError(ERR_CAMERA, "image no longer available"); return; }
    Serial.printf("[CAP] resending image %u\n", imageId);
    // Resends always use ACKed indications: slower but no gaps possible.
    if (!ble::sendImage(s, IMG_FLAG_RESEND, false) && ble::connected()) ble::reset();
}

static void tap() {
    pressedAtMs = millis();
    Serial.printf("\n[BTN] tap at %lu ms\n", (unsigned long)pressedAtMs);
    ble::sendEvent(EVT_BUTTON_DOWN);
    captureAndSend(MODE_TAP);
}

static void simulateHold() { Serial.println("[BTN] hold-to-talk arrives in Phase 3 milestone M6"); }

// ============================ Mic level monitor (Diagnostics) ============================

static volatile bool micMonitorOn = false;

static void monitorBlock(const int16_t* pcm, size_t n, void*) {
    static double sum = 0;
    static int peak = 0, blocks = 0;
    for (size_t i = 0; i < n; i++) { sum += (double)pcm[i] * pcm[i]; peak = max(peak, abs((int)pcm[i])); }
    if (++blocks < 10) return;  // 10 x 25 ms = report 4 times a second
    float rms = sqrt(sum / (n * blocks));
    auto db = [](float v) { return v <= 1 ? -9600 : (int)(2000.0f * log10f(v / 32768.0f)); };
    uint8_t p[4];
    put_u16(p, (uint16_t)(int16_t)db(rms));
    put_u16(p + 2, (uint16_t)(int16_t)db(peak));
    ble::sendEvent(EVT_MIC_LEVEL, p, 4);
    sum = 0; peak = 0; blocks = 0;
}

// ============================ Commands ============================

static void handleCommand(const uint8_t* c, size_t n) {
    switch (c[0]) {
        case CMD_CAPTURE:
            pressedAtMs = millis();
            captureAndSend(MODE_PHONE);
            break;
        case CMD_STOP_PLAYBACK: speaker::stop(); break;
        case CMD_SET_VOLUME:
            if (n >= 2) { settings::get().volume = min<uint8_t>(c[1], 100); settings::save(); speaker::applyVolume(); }
            break;
        case CMD_PLAY_TONE:
            if (n >= 5 && !mic::recording()) speaker::tone(min<uint16_t>(get_u16(c + 1), 8000), min<uint16_t>(get_u16(c + 3), 5000));
            break;
        case CMD_GET_CAPS: sendCaps(); break;
        case CMD_PING:
            if (n >= 5) ble::sendEvent(EVT_PONG, c + 1, 4);
            break;
        case CMD_RESEND_IMAGE:
            if (n >= 3) resendLast(get_u16(c + 1));
            break;
        case CMD_ABORT_IMAGE: break;  // handled immediately in the BLE callback
        case CMD_THROUGHPUT_UP:
            if (n >= 5) throughput::runUp(min<uint32_t>(get_u32(c + 1), 1024 * 1024));
            break;
        case CMD_THROUGHPUT_DOWN:
            if (n >= 5) throughput::armDown(get_u32(c + 1));
            break;
        case CMD_SET_PARAM: {
            if (n < 4) break;
            uint16_t v = get_u16(c + 2);
            Settings& s = settings::get();
            switch (c[1]) {
                case PARAM_HOLD_MS: if (v >= 200 && v <= 2000) s.holdMs = v; break;
                case PARAM_TOF_SAMPLES: if (v >= 1 && v <= 15) s.tofSamples = v; break;
                case PARAM_MIC_GAIN_X10: if (v >= 1 && v <= 160) s.micGainX10 = v; break;
                case PARAM_CEILING: if (v >= 1 && v <= 100) { s.ceilingPct = v; speaker::applyVolume(); } break;
                case PARAM_DATA_LEN: ble::setDataLength(v); break;  // experiments, not saved
                case PARAM_IMAGE_MODE:
                    imageViaNotify = v == 1;
                    Serial.printf("[BLE] photo transfer mode: %s\n", imageViaNotify ? "notify (fast)" : "indicate");
                    break;
            }
            settings::save();
            sendCaps();
            break;
        }
        case CMD_MIC_MONITOR:
            if (n >= 2 && c[1] && !mic::recording() && !speaker::busy()) micMonitorOn = mic::start(monitorBlock, nullptr);
            else if (n >= 2 && !c[1] && micMonitorOn) { mic::stop(); micMonitorOn = false; }
            break;
        case CMD_TOF_READ:
            if (n >= 2) sendTof(TOF_LIVE_IMAGE_ID, tof::measure(c[1]));
            break;
        case CMD_EARCON:
            if (n >= 2 && c[1] <= 3 && !mic::recording()) speaker::earcon((speaker::Earcon)c[1]);
            break;
        default:
            sendError(ERR_BAD_COMMAND, "unknown command");
    }
}

static void onAudioDown(const uint8_t* d, size_t len) {
    if (len < AUDIO_HDR_LEN) return;
    if (d[0] == STREAM_THROUGHPUT) throughput::onDownFrame(d, len);
    else if (d[0] == STREAM_SPEECH) speaker::onSpeechFrame(d, len);
}

// ============================ Button ============================

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

// ============================ Arduino ============================

void setup() {
    Serial.begin(115200);
    delay(1500);  // let USB CDC enumerate so early logs aren't lost
    Serial.printf("\n[SYS] SmartGlasses firmware %s, protocol v%d\n", FW_VERSION_STR, PROTOCOL_VERSION);
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
    ble::begin(onAudioDown);
    console::begin(tap, simulateHold);
    if (speaker::ok()) speaker::earcon(speaker::Earcon::Ready);
}

void loop() {
    if (!ble::connected()) imageViaNotify = false;  // each app session opts in again
    if (buttonPressed()) tap();
    uint8_t cmd[24];
    if (size_t n = ble::pollCommand(cmd, sizeof cmd)) handleCommand(cmd, n);
    throughput::poll();
    console::poll();
    delay(2);
}
