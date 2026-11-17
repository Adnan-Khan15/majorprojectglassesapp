// BLE GATT server: the glasses side of the protocol in glasses_protocol.h /
// glasses_frames.h. Commands are queued to the main loop (never run on the BLE
// host task, because image sending blocks waiting for ACKs that arrive there).
#pragma once
#include <stddef.h>
#include <stdint.h>
#include "camera.h"

namespace ble {

/** Called on the BLE host task for every AUDIO_DOWN write. Must be quick. */
typedef void (*AudioDownFn)(const uint8_t* data, size_t len);

void begin(AudioDownFn onAudioDown);
bool connected();
/** CONTROL + IMAGE_DATA subscribed: photos can be sent. */
bool imagesReady();
uint16_t mtu();
/** Largest payload for one notification/indication (MTU - 3, capped at 244). */
size_t maxPayload();

/** Notifies an event: [type][millis u32][payload]. False if not subscribed / no buffers. */
bool sendEvent(uint8_t type, const uint8_t* payload = nullptr, size_t len = 0);
/** v2 header + JPEG chunks as indications, each waiting for the phone's ACK. */
bool sendImage(const camera::Shot& shot, uint8_t flags);
/** Notifies one AUDIO_UP frame, retrying briefly when the stack is out of buffers. */
bool notifyAudio(const uint8_t* frame, size_t len, uint16_t* retries = nullptr);

/** Pops one queued COMMAND write (main loop). Returns its length, 0 if none. */
size_t pollCommand(uint8_t* buf, size_t cap);
/** Phone sent ABORT_IMAGE: stop the current image transfer at the next chunk. */
void requestImageAbort();
/** Drops the connection (after a stuck indication) so the phone reconnects quickly. */
void reset();
/** Requests a larger LL data length (DLE) on the current connection. */
void setDataLength(uint16_t octets);

}  // namespace ble
