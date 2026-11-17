// VL53L0X distance sensor on Wire (SDA GPIO9, SCL GPIO7). Never blocks the board if
// the sensor is missing: every call returns a result with TOF_FLAG_NO_SENSOR instead.
#pragma once
#include <stdint.h>
#include "tof_filter.h"

namespace tof {
bool begin();
bool ok();
/** Blocking: N samples with the configured timing budget (~N x 200 ms), reduced to median/spread/flags. */
tof_result_t measure(uint8_t samples);
/** Starts measure() on a background task (so it overlaps the image transfer). */
void startAsync(uint8_t samples);
/** Waits for the background measurement; returns false on timeout (result then has no VALID flag). */
bool waitAsync(tof_result_t& out, uint32_t timeoutMs);
}
