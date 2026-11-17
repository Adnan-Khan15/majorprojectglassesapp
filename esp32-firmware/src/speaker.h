// MAX98357A speaker on I2S1 (standard TX), 16 kHz, 16-bit. The same sample is sent on
// both L and R, so whatever the amp's SD-pin channel mode is, it plays the full signal.
// Loudness is capped by a software ceiling (default 50% of full scale) to protect the
// 1 W / 8 ohm speaker; volume is a percentage OF that ceiling.
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace speaker {

constexpr uint32_t SAMPLE_RATE = 16000;

enum class Earcon : uint8_t { Listening, Thinking, Error, Ready };

bool begin();
bool ok();
/** Applies the volume/ceiling from settings (call after changing them). */
void applyVolume();
/** Queues a tone (hz = 0 is a gap). Returns immediately. */
void tone(uint16_t hz, uint16_t ms);
void earcon(Earcon e);
/** True while anything is queued, playing, or still draining from DMA. */
bool busy();
/** Blocks until idle or the timeout passes. */
void waitIdle(uint32_t timeoutMs);
/** Stops tones and any stream immediately (barge-in). */
void stop();

}  // namespace speaker
