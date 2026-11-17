#include "camera.h"
#include <Arduino.h>
#include "esp_camera.h"

namespace camera {

// XIAO ESP32S3 Sense onboard camera pin map (from reference/hardware_bringup.ino).
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

// VGA keeps print legible for OCR while staying small enough to send quickly.
static constexpr framesize_t CAPTURE_SIZE = FRAMESIZE_VGA;
static constexpr int JPEG_QUALITY = 12;  // 0-63, lower = better quality, bigger file
// A VGA JPEG under this is almost certainly a black/blank frame.
static constexpr size_t SUSPICIOUSLY_SMALL_JPEG = 8000;

static bool isOk = false;
static uint32_t readyMs = 0;
static uint8_t pid = 0;
static uint8_t* buf = nullptr;
static size_t bufCap = 0;
static Shot lastShot;
static uint32_t count = 0;
static uint16_t nextId = 1;

bool begin() {
    camera_config_t c = {};
    c.ledc_channel = LEDC_CHANNEL_0;
    c.ledc_timer = LEDC_TIMER_0;
    c.pin_d0 = Y2_GPIO_NUM; c.pin_d1 = Y3_GPIO_NUM; c.pin_d2 = Y4_GPIO_NUM; c.pin_d3 = Y5_GPIO_NUM;
    c.pin_d4 = Y6_GPIO_NUM; c.pin_d5 = Y7_GPIO_NUM; c.pin_d6 = Y8_GPIO_NUM; c.pin_d7 = Y9_GPIO_NUM;
    c.pin_xclk = XCLK_GPIO_NUM; c.pin_pclk = PCLK_GPIO_NUM;
    c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
    c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
    c.pin_pwdn = PWDN_GPIO_NUM; c.pin_reset = RESET_GPIO_NUM;
    c.xclk_freq_hz = 20000000;
    c.pixel_format = PIXFORMAT_JPEG;
    c.frame_size = CAPTURE_SIZE;
    c.jpeg_quality = JPEG_QUALITY;
    c.fb_location = CAMERA_FB_IN_PSRAM;
    // Two buffers + GRAB_LATEST: the sensor keeps capturing, so a press gets a frame from "now".
    c.fb_count = 2;
    c.grab_mode = CAMERA_GRAB_LATEST;

    esp_err_t err = esp_camera_init(&c);
    if (err != ESP_OK) {
        Serial.printf("[CAM] init failed, error 0x%x\n", err);
        return isOk = false;
    }
    sensor_t* s = esp_camera_sensor_get();
    s->set_vflip(s, 1);    // mounted upside down
    s->set_hmirror(s, 1);
    pid = s->id.PID;
    Serial.printf("[CAM] sensor PID 0x%02x (0x26 = OV2640, 0x36 = OV3660)\n", pid);
    readyMs = millis();
    return isOk = true;
}

bool ok() { return isOk; }
const Shot& last() { return lastShot; }

bool capture(Shot& out) {
    if (!isOk) return false;
    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) { Serial.println("[CAM] capture failed"); return false; }
    size_t firstLen = 0;
    // Diagnostic mitigation, NOT a root-cause fix: a black ~3.6 KB frame was seen once and
    // its cause is unknown. Grab one more if the frame is tiny; both sizes are reported.
    if (fb->len < SUSPICIOUSLY_SMALL_JPEG) {
        firstLen = fb->len;
        esp_camera_fb_return(fb);
        fb = esp_camera_fb_get();
        if (!fb) { Serial.println("[CAM] capture failed on retry"); return false; }
        Serial.printf("[DIAG] small frame %u bytes -> retried, new frame %u bytes\n", (unsigned)firstLen, (unsigned)fb->len);
    }
    if (fb->len > bufCap) {
        free(buf);
        bufCap = fb->len + 16 * 1024;
        buf = (uint8_t*)ps_malloc(bufCap);
        if (!buf) { bufCap = 0; esp_camera_fb_return(fb); Serial.println("[CAM] out of PSRAM"); return false; }
    }
    memcpy(buf, fb->buf, fb->len);
    lastShot.jpeg = buf;
    lastShot.len = fb->len;
    lastShot.width = fb->width;
    lastShot.height = fb->height;
    lastShot.id = nextId++;
    if (nextId == 0xFFFF) nextId = 1;  // 0xFFFF is reserved for "live ToF" readings
    lastShot.index = ++count;
    lastShot.takenAtMs = millis();
    lastShot.sinceInitMs = lastShot.takenAtMs - readyMs;
    lastShot.firstLen = firstLen;
    lastShot.sensorPid = pid;
    esp_camera_fb_return(fb);
    if (lastShot.len < SUSPICIOUSLY_SMALL_JPEG) {
        Serial.println("[CAM] warning: very small JPEG - lens covered, too dark, or camera not ready?");
    }
    Serial.printf("[DIAG] capture #%lu id %u, %ux%u, %u bytes, %lu ms after camera init, free heap %u, PSRAM %u\n",
                  (unsigned long)lastShot.index, lastShot.id, lastShot.width, lastShot.height, (unsigned)lastShot.len,
                  (unsigned long)lastShot.sinceInitMs, (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getFreePsram());
    out = lastShot;
    return true;
}

}  // namespace camera
