package com.smartglasses.app.ble

import java.util.UUID

/** GATT contract — keep in sync with esp32-firmware/include/glasses_protocol.h. */
object GlassesProtocol {
    const val DEVICE_NAME = "SmartGlasses"
    val SERVICE: UUID = UUID.fromString("6e1a0001-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    val CONTROL: UUID = UUID.fromString("6e1a0002-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    val IMAGE_DATA: UUID = UUID.fromString("6e1a0003-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    val RESULT_TEXT: UUID = UUID.fromString("6e1a0004-4b7d-4f2a-9c3e-5a1b2c3d4e5f")
    const val MAX_MTU = 517
}
