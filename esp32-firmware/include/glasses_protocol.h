// GATT contract shared with the Android app (keep in sync with
// android-app/.../ble/GlassesProtocol.kt).
#pragma once

#define GLASSES_DEVICE_NAME "SmartGlasses"

#define SERVICE_UUID      "6e1a0001-4b7d-4f2a-9c3e-5a1b2c3d4e5f"
// ESP32 -> phone, indicate. One per capture, sent before the image:
// 6 bytes {uint32 jpegSize LE, uint16 distanceMm LE}; distanceMm 0 = no valid reading.
#define CONTROL_UUID      "6e1a0002-4b7d-4f2a-9c3e-5a1b2c3d4e5f"
// ESP32 -> phone, indicate. JPEG streamed in (MTU - 3)-byte chunks, in order,
// each sent only after the previous one was acknowledged.
#define IMAGE_DATA_UUID   "6e1a0003-4b7d-4f2a-9c3e-5a1b2c3d4e5f"
// phone -> ESP32, write. UTF-8 sentence to speak.
#define RESULT_TEXT_UUID  "6e1a0004-4b7d-4f2a-9c3e-5a1b2c3d4e5f"

#define CONTROL_HEADER_LEN 6

// Largest ATT MTU NimBLE will accept; the phone requests 517.
#define GLASSES_MAX_MTU 517
