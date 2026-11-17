#include "speaker.h"
#include <Arduino.h>
#include <driver/i2s.h>
#include <math.h>
#include "pins.h"
#include "settings.h"

namespace speaker {

static constexpr i2s_port_t PORT = I2S_NUM_1;  // I2S0 is reserved for the PDM mic
static constexpr int DMA_BUF_LEN = 256;        // samples per DMA buffer
static constexpr int DMA_BUF_COUNT = 6;        // ~96 ms of buffering
static constexpr int BLOCK = 128;              // samples generated per write
static constexpr uint32_t DRAIN_MS = (DMA_BUF_LEN * DMA_BUF_COUNT * 1000) / SAMPLE_RATE + 10;
static constexpr int EDGE_SAMPLES = 80;        // 5 ms fade in/out on every tone: no clicks

struct ToneCmd { uint16_t hz; uint16_t ms; uint32_t gen; };

static bool isOk = false;
static QueueHandle_t toneQ = nullptr;
static TaskHandle_t task = nullptr;
static volatile bool active = false;           // generating samples right now
static volatile uint32_t drainUntil = 0;       // millis() until DMA is empty
// stop() bumps the generation; queued/playing items from an older generation are dropped.
static volatile uint32_t generation = 0;
static volatile int32_t gainQ15 = 0;           // 0..32767 applied to full-scale samples

void applyVolume() {
    const Settings& s = settings::get();
    gainQ15 = (int32_t)(32767L * s.ceilingPct / 100 * s.volume / 100);
}

static void writeStereo(const int16_t* mono, int n) {
    int16_t st[BLOCK * 2];
    for (int i = 0; i < n; i++) { st[2 * i] = mono[i]; st[2 * i + 1] = mono[i]; }
    size_t written = 0;
    i2s_write(PORT, st, (size_t)n * 4, &written, portMAX_DELAY);
}

static void playTone(const ToneCmd& t) {
    const int total = (int)((uint32_t)t.ms * SAMPLE_RATE / 1000);
    const float w = 2.0f * (float)M_PI * t.hz / SAMPLE_RATE;
    int16_t buf[BLOCK];
    float phase = 0;
    for (int done = 0; done < total && t.gen == generation;) {
        int n = min(BLOCK, total - done);
        for (int i = 0; i < n; i++, done++) {
            float env = 1.0f;
            if (done < EDGE_SAMPLES) env = (float)done / EDGE_SAMPLES;
            else if (total - done < EDGE_SAMPLES) env = (float)(total - done) / EDGE_SAMPLES;
            float v = t.hz ? sinf(phase) * env : 0.0f;
            phase += w;
            if (phase > 2 * (float)M_PI) phase -= 2 * (float)M_PI;
            buf[i] = (int16_t)(v * gainQ15);
        }
        writeStereo(buf, n);
    }
}

static void audioTask(void*) {
    ToneCmd t;
    for (;;) {
        if (xQueueReceive(toneQ, &t, portMAX_DELAY) == pdTRUE) {
            active = true;
            if (t.gen == generation) playTone(t);
            if (uxQueueMessagesWaiting(toneQ) == 0) {
                active = false;
                drainUntil = millis() + DRAIN_MS;
            }
        }
    }
}

bool begin() {
    i2s_config_t cfg = {};
    cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
    cfg.sample_rate = SAMPLE_RATE;
    cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
    cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
    cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
    cfg.intr_alloc_flags = 0;
    cfg.dma_buf_count = DMA_BUF_COUNT;
    cfg.dma_buf_len = DMA_BUF_LEN;
    cfg.use_apll = false;
    cfg.tx_desc_auto_clear = true;  // DMA outputs silence when we have nothing to play
    esp_err_t err = i2s_driver_install(PORT, &cfg, 0, nullptr);
    if (err != ESP_OK) {
        Serial.printf("[SPK] I2S1 driver install failed: 0x%x\n", err);
        return isOk = false;
    }
    i2s_pin_config_t pins = {};
    pins.mck_io_num = I2S_PIN_NO_CHANGE;
    pins.bck_io_num = PIN_I2S_BCLK;
    pins.ws_io_num = PIN_I2S_LRC;
    pins.data_out_num = PIN_I2S_DOUT;
    pins.data_in_num = I2S_PIN_NO_CHANGE;
    err = i2s_set_pin(PORT, &pins);
    if (err != ESP_OK) {
        Serial.printf("[SPK] I2S1 pin config failed: 0x%x\n", err);
        return isOk = false;
    }
    i2s_zero_dma_buffer(PORT);
    applyVolume();
    toneQ = xQueueCreate(16, sizeof(ToneCmd));
    xTaskCreatePinnedToCore(audioTask, "spk", 6144, nullptr, 5, &task, 1);
    Serial.printf("[SPK] I2S1 TX ready: BCLK %d, LRC %d, DOUT %d, %lu Hz; ceiling %u%%, volume %u%%\n",
                  PIN_I2S_BCLK, PIN_I2S_LRC, PIN_I2S_DOUT, (unsigned long)SAMPLE_RATE,
                  settings::get().ceilingPct, settings::get().volume);
    return isOk = true;
}

bool ok() { return isOk; }

void tone(uint16_t hz, uint16_t ms) {
    if (!isOk) return;
    ToneCmd t{hz, ms, generation};
    active = true;  // set before the task picks it up, so busy() is true immediately
    xQueueSend(toneQ, &t, 0);
}

void earcon(Earcon e) {
    switch (e) {
        case Earcon::Listening: tone(660, 60); tone(990, 90); break;           // rising: "speak now"
        case Earcon::Thinking:  tone(520, 70); tone(0, 60); tone(520, 70); break;
        case Earcon::Error:     tone(330, 250); break;
        case Earcon::Ready:     tone(880, 80); break;
    }
}

bool busy() { return active || uxQueueMessagesWaiting(toneQ) > 0 || (int32_t)(millis() - drainUntil) < 0; }

void waitIdle(uint32_t timeoutMs) {
    uint32_t t0 = millis();
    while (busy() && millis() - t0 < timeoutMs) delay(5);
}

void stop() {
    if (!isOk) return;
    generation = generation + 1;
    xQueueReset(toneQ);
    i2s_zero_dma_buffer(PORT);
}

}  // namespace speaker
