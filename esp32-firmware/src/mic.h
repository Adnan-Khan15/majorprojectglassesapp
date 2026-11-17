// Onboard PDM microphone on I2S0 (PDM RX), 16 kHz, 16-bit mono out.
// Both PDM slots are read and the one carrying signal is picked per recording,
// because which slot the onboard mic uses must be confirmed on hardware.
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace mic {

constexpr uint32_t SAMPLE_RATE = 16000;
constexpr size_t BLOCK_SAMPLES = 400;  // 25 ms = one BLE audio frame

/** Called on the mic task for every block of BLOCK_SAMPLES mono samples. */
typedef void (*BlockFn)(const int16_t* pcm, size_t n, void* ctx);

bool begin();
bool ok();
/** Starts streaming blocks to fn until stop(). False if already recording or no mic. */
bool start(BlockFn fn, void* ctx);
void stop();
bool recording();

struct Level { float rmsL, peakL, rmsR, peakR; };  // dBFS
/** Blocking level meter over `ms` (not while recording). Raw slots, before gain. */
Level measure(uint32_t ms);

}  // namespace mic
