// BLE throughput spike (Phase 3 M3): measures what the link really carries, both
// ways, so the audio design (ADPCM vs raw PCM, live streaming) is based on data.
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace throughput {
/** Uplink: sends `bytes` of test payload as AUDIO_UP notifications (stream 3), then a result event. */
void runUp(uint32_t bytes);
/** Downlink: phone announced `bytes`; counters reset, frames arrive via onDownFrame. */
void armDown(uint32_t bytes);
/** AUDIO_DOWN frame for stream 3 (BLE host task). */
void onDownFrame(const uint8_t* data, size_t len);
/** Main loop: reports the downlink result once the END frame arrived or the link went idle. */
void poll();
}
