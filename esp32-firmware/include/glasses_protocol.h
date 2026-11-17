// GATT contract shared with the Android app (keep in sync with
// android-app/.../ble/GlassesProtocol.kt).
#pragma once

#define FW_VERSION_STR "0.4.0"
#define FW_MAJOR 0
#define FW_MINOR 4
#define FW_PATCH 0
// v2: versioned 16-byte image header (id + CRC32), ToF sent as an event after the
// image, COMMAND / EVENT / AUDIO_UP / AUDIO_DOWN. Frame layouts: lib/glasses_core/src/glasses_frames.h
#define PROTOCOL_VERSION 2

#define GLASSES_DEVICE_NAME "SmartGlasses"

#define SERVICE_UUID      "6e1a0001-4b7d-4f2a-9c3e-5a1b2c3d4e5f"
// ESP32 -> phone, indicate. One per capture, sent before the image.
// v1 (old firmware): 6 bytes {uint32 jpegSize, uint16 distanceMm}; 0 mm = no reading.
// v2: 16 bytes, see img_header_t; the distance follows as an EVT_TOF_RESULT event.
#define CONTROL_UUID      "6e1a0002-4b7d-4f2a-9c3e-5a1b2c3d4e5f"
// ESP32 -> phone, indicate. JPEG streamed in (MTU - 3)-byte chunks, in order,
// each sent only after the previous one was acknowledged.
#define IMAGE_DATA_UUID   "6e1a0003-4b7d-4f2a-9c3e-5a1b2c3d4e5f"
// phone -> ESP32, write. UTF-8 sentence to speak.
#define RESULT_TEXT_UUID  "6e1a0004-4b7d-4f2a-9c3e-5a1b2c3d4e5f"
// v2 additions (see glasses_frames.h for every byte):
#define COMMAND_UUID      "6e1a0005-4b7d-4f2a-9c3e-5a1b2c3d4e5f"  // phone -> ESP32, write
#define EVENT_UUID        "6e1a0006-4b7d-4f2a-9c3e-5a1b2c3d4e5f"  // ESP32 -> phone, notify
#define AUDIO_UP_UUID     "6e1a0007-4b7d-4f2a-9c3e-5a1b2c3d4e5f"  // ESP32 -> phone, notify
#define AUDIO_DOWN_UUID   "6e1a0008-4b7d-4f2a-9c3e-5a1b2c3d4e5f"  // phone -> ESP32, write without response

#define CONTROL_HEADER_V1_LEN 6

// Largest ATT MTU NimBLE will accept; the phone requests 517.
#define GLASSES_MAX_MTU 517
