/*
  hardware_bringup.ino
  ---------------------------------------------------------------
  Smart Assistive Glasses for the Visually Impaired — BE Project
  Phase 1 hardware bring-up sketch.

  Board   : Seeed XIAO ESP32S3 Sense (onboard OV2640 / OV3660 camera)
  Sensor  : VL53L0X / VL53L1X Time-of-Flight distance sensor (I2C)
  Purpose : Prove the camera and ToF sensor initialise and read
            together on one board, over the shared I2C bus — the
            sensing-layer foundation that the FOMO detection +
            camera-ToF audio-fusion logic (Phase 2) will build on.
            This sketch does NOT yet run the FOMO model; it only
            validates the sensing layer described on slide 7.

  Wiring  : VL53L1X -> XIAO default I2C pins (SDA = D4, SCL = D5)
            Camera is onboard — no wiring needed.

  Library : "VL53L1X" by Pololu (install via Arduino Library Manager)
            esp_camera.h ships with the ESP32 Arduino core.

  Output  : Serial monitor @ 115200 baud, one line per loop, e.g.
              frame=160x120 (5211 bytes)   dist=842 mm
*/

#include <Wire.h>
#include <VL53L1X.h>
#include "esp_camera.h"

// ---- XIAO ESP32S3 Sense onboard camera pin map (OV2640 / OV3660) ----
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

VL53L1X tof;

bool initCamera() {
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
  config.pin_sscb_sda = SIOD_GPIO_NUM;
  config.pin_sscb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn  = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz  = 20000000;
  config.pixel_format  = PIXFORMAT_JPEG;    // swap to PIXFORMAT_GRAYSCALE for FOMO in Phase 2
  config.frame_size    = FRAMESIZE_QQVGA;   // 160x120 -- resized to the FOMO input size in Phase 2
  config.fb_location   = CAMERA_FB_IN_PSRAM;
  config.fb_count      = 1;
  config.grab_mode     = CAMERA_GRAB_WHEN_EMPTY;
  config.jpeg_quality  = 12;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("Camera init failed, error 0x%x\n", err);
    return false;
  }
  return true;
}

void setup() {
  Serial.begin(115200);
  while (!Serial) { delay(10); }
  delay(300);

  Serial.println("=== Smart Assistive Glasses -- hardware bring-up ===");

  if (!initCamera()) {
    Serial.println("!! Camera FAILED to initialise. Halting.");
    while (true) delay(1000);
  }
  Serial.println("Camera OK (OV2640/3660, QQVGA JPEG).");

  Wire.begin();
  tof.setTimeout(500);
  if (!tof.init()) {
    Serial.println("!! VL53L1X FAILED to initialise. Check wiring. Halting.");
    while (true) delay(1000);
  }
  tof.setDistanceMode(VL53L1X::Long);      // up to ~4 m, forward-facing cone
  tof.setMeasurementTimingBudget(50000);   // 50 ms budget
  tof.startContinuous(50);                 // sample every 50 ms
  Serial.println("VL53L1X ToF sensor OK.");
  Serial.println("--- entering loop: frame capture + distance read ---");
}

void loop() {
  camera_fb_t *fb = esp_camera_fb_get();
  uint16_t distance_mm = tof.read();

  if (!fb) {
    Serial.println("Camera capture failed");
  } else {
    Serial.printf("frame=%ux%u (%u bytes)   dist=%u mm", fb->width, fb->height, fb->len, distance_mm);
    if (tof.timeoutOccurred()) Serial.print("  [ToF TIMEOUT]");
    Serial.println();
    esp_camera_fb_return(fb);
  }

  delay(200);  // ~5 Hz bring-up rate; becomes event-driven once FOMO + audio fusion (Phase 2) are added
}
