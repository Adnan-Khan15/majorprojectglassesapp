package com.smartglasses.app.ble

import java.util.UUID

/**
 * GATT contract: keep in sync with esp32-firmware/include/glasses_protocol.h.
 * Every byte of every frame is defined in [Frames] (twin of glasses_frames.h).
 */
object GlassesProtocol {
    const val DEVICE_NAME = "SmartGlasses"
    val SERVICE: UUID = UUID.fromString("6e1a0001-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    /** ESP32 -> phone, indicate: image header, v1 (6 B) or v2 (16 B). */
    val CONTROL: UUID = UUID.fromString("6e1a0002-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    /** ESP32 -> phone, indicate: the JPEG in ordered chunks of <= 244 B. */
    val IMAGE_DATA: UUID = UUID.fromString("6e1a0003-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    /** phone -> ESP32, write: UTF-8 sentence (logged by the board). */
    val RESULT_TEXT: UUID = UUID.fromString("6e1a0004-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    /** v2: phone -> ESP32, write: [op][payload]. */
    val COMMAND: UUID = UUID.fromString("6e1a0005-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    /** v2: ESP32 -> phone, notify: [type][board ms u32][payload]. */
    val EVENT: UUID = UUID.fromString("6e1a0006-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    /** v2: ESP32 -> phone, notify: audio frames (mic). */
    val AUDIO_UP: UUID = UUID.fromString("6e1a0007-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    /** v2: phone -> ESP32, write without response: audio frames (speech). */
    val AUDIO_DOWN: UUID = UUID.fromString("6e1a0008-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    const val MAX_MTU = 517
    /** Sanity cap; a VGA JPEG is ~20-60 KB. */
    const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
}
