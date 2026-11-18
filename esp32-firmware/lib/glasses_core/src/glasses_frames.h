// BLE protocol v2 frame layouts. ALL multi-byte fields are little-endian.
// Kotlin twin: android-app/.../ble/Frames.kt. Change both together, and the
// tests on both sides (test/test_core, FramesTest.kt) use the same byte examples.
//
// Characteristics (service 6e1a0001-...):
//   0002 CONTROL     indicate  image header (v1: 6 B, v2: 16 B)
//   0003 IMAGE_DATA  indicate  JPEG chunks, in order, <= 244 B
//   0004 RESULT_TEXT write     UTF-8 sentence (logged; kept for compatibility)
//   0005 COMMAND     write     phone -> glasses commands     [op u8][payload]
//   0006 EVENT       notify    glasses -> phone events       [type u8][board_ms u32][payload]
//   0007 AUDIO_UP    notify    mic audio / test payload      [audio header 8 B][data]
//   0008 AUDIO_DOWN  write-NR  speech audio / test payload   [audio header 8 B][data]
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ---- image header v2 (CONTROL) ----
#define IMG_HDR_V2_LEN 16
#define IMG_FLAG_QUESTION 0x01   // photo belongs to a hold-to-talk question
#define IMG_FLAG_RETRIED  0x02   // first frame was suspiciously small; this is the retry
#define IMG_FLAG_RESEND   0x04   // resent after RESEND_IMAGE
#define IMG_FLAG_NOTIFY   0x08   // chunks follow as AUDIO_UP notifications, stream STREAM_IMAGE (not IMAGE_DATA)
typedef struct {
    uint8_t version;   // 2
    uint8_t flags;
    uint16_t image_id;
    uint32_t size;
    uint32_t crc32;
    uint16_t width, height;
} img_header_t;

// ---- audio frames (AUDIO_UP / AUDIO_DOWN) ----
#define AUDIO_HDR_LEN 8
#define AUDIO_FLAG_START 0x01
#define AUDIO_FLAG_END   0x02
#define AUDIO_CODEC_RAW   0
#define AUDIO_CODEC_ADPCM 1
#define STREAM_QUESTION   1   // up: hold-to-talk mic audio
#define STREAM_SPEECH     2   // down: spoken answer
#define STREAM_THROUGHPUT 3   // either way: test payload
#define STREAM_MIC_TEST   4   // up: Diagnostics record-and-play-back
#define STREAM_IMAGE      5   // up: photo chunks in fast (notify) mode, codec RAW
typedef struct {
    uint8_t stream;
    uint8_t flags;
    uint16_t seq;
    int16_t predictor;    // ADPCM state at the START of this frame's data
    uint8_t step_index;
    uint8_t codec;
} audio_header_t;

// ---- events (EVENT) ----
#define EVT_PREFIX_LEN 5
enum {
    EVT_BUTTON_DOWN = 0x01,      // -
    EVT_BUTTON_UP = 0x02,        // u16 held_ms
    EVT_HOLD_DETECTED = 0x03,    // -
    EVT_RECORD_START = 0x04,     // u8 stream
    EVT_RECORD_STOP = 0x05,      // u8 stream, u16 frames, u8 reason (0 released, 1 time cap, 2 error, 3 cancelled)
    EVT_PHOTO_CAPTURED = 0x06,   // u16 image_id, u32 size, u16 press_to_capture_ms, u16 rejected_first_len, u8 mode
    EVT_TOF_RESULT = 0x07,       // u16 image_id (0xFFFF = live), u16 median_mm, u8 flags, u8 valid, u8 samples, u16 spread_mm
    EVT_PLAY_STARTED = 0x08,     // u8 stream
    EVT_PLAY_DONE = 0x09,        // u8 stream, u8 reason (0 finished, 1 stopped, 2 error), u16 underruns
    EVT_UNDERRUN = 0x0A,         // u8 stream, u16 count so far
    EVT_CREDITS = 0x0B,          // u8 stream, u16 allowed_seq (may send seq < allowed, mod 2^16)
    EVT_ERROR = 0x0C,            // u8 code, ASCII text
    EVT_CAPS = 0x0D,             // caps_t (CAPS_LEN bytes)
    EVT_PONG = 0x0E,             // u32 token
    EVT_THROUGHPUT_RESULT = 0x0F,// u8 dir (0 up, 1 down), u32 bytes, u16 frames, u16 missing, u32 duration_ms, u16 retries
    EVT_MIC_LEVEL = 0x10,        // i16 rms (dBFS x100), i16 peak (dBFS x100)
    EVT_IMAGE_ABORTED = 0x11,    // u16 image_id, u8 reason (0 phone abort, 1 link error)
};
#define TOF_LIVE_IMAGE_ID 0xFFFF
#define MODE_TAP 0
#define MODE_QUESTION 1
#define MODE_PHONE 2

// Error codes (EVT_ERROR)
enum {
    ERR_CAMERA = 1, ERR_NOT_SUBSCRIBED = 2, ERR_BUSY = 3, ERR_MIC = 4, ERR_SPEAKER = 5, ERR_BAD_COMMAND = 6,
};

// ---- commands (COMMAND) ----
enum {
    CMD_CAPTURE = 0x01,          // u8 mode
    CMD_STOP_PLAYBACK = 0x02,    // -
    CMD_SET_VOLUME = 0x03,       // u8 volume (% of ceiling)
    CMD_PLAY_TONE = 0x04,        // u16 hz, u16 ms
    CMD_GET_CAPS = 0x05,         // -
    CMD_PING = 0x06,             // u32 token
    CMD_RESEND_IMAGE = 0x07,     // u16 image_id
    CMD_ABORT_IMAGE = 0x08,      // -
    CMD_THROUGHPUT_UP = 0x09,    // u32 bytes
    CMD_THROUGHPUT_DOWN = 0x0A,  // u32 bytes (announces a test on AUDIO_DOWN)
    CMD_SET_PARAM = 0x0B,        // u8 param, u16 value
    CMD_MIC_MONITOR = 0x0C,      // u8 on
    CMD_TOF_READ = 0x0D,         // u8 samples
    CMD_EARCON = 0x0E,           // u8 earcon (0 listening, 1 thinking, 2 error, 3 ready)
    CMD_MIC_TEST = 0x0F,         // u8 seconds: record and stream on STREAM_MIC_TEST
};
enum {
    PARAM_HOLD_MS = 1, PARAM_TOF_SAMPLES = 2, PARAM_MIC_GAIN_X10 = 3, PARAM_CEILING = 4,
    PARAM_DATA_LEN = 5,     // LL data length request (experiments, not saved)
    PARAM_IMAGE_MODE = 6,   // 0 = indications on IMAGE_DATA (default), 1 = notifications on AUDIO_UP stream 5
};

// ---- capabilities (EVT_CAPS payload) ----
#define CAPS_LEN 25
#define FEAT_CAMERA 0x01
#define FEAT_TOF    0x02
#define FEAT_SPEAKER 0x04
#define FEAT_MIC    0x08
#define FEAT_PSRAM  0x10
#define FEAT_IMG_NOTIFY 0x20     // supports PARAM_IMAGE_MODE = 1
#define FEAT_SPEECH_STREAM 0x40  // plays STREAM_SPEECH audio from AUDIO_DOWN with credits
#define FEAT_HOLD_TO_TALK 0x80   // tap/hold button + STREAM_QUESTION mic audio
#define TOF_TYPE_NONE 0
#define TOF_TYPE_VL53L0X 1
typedef struct {
    uint8_t protocol;
    uint8_t fw_major, fw_minor, fw_patch;
    uint32_t features;
    uint16_t audio_rate;
    uint16_t samples_per_frame;
    uint8_t codec;
    uint8_t tof_type;
    uint8_t reset_reason;
    uint8_t volume;
    uint8_t ceiling;
    uint16_t hold_ms;
    uint16_t ring_frames;
    uint16_t prebuffer_frames;
    uint8_t tof_samples;
} caps_t;

// ---- little-endian helpers ----
static inline void put_u16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static inline void put_u32(uint8_t* p, uint32_t v) { put_u16(p, (uint16_t)v); put_u16(p + 2, (uint16_t)(v >> 16)); }
static inline uint16_t get_u16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static inline uint32_t get_u32(const uint8_t* p) { return get_u16(p) | ((uint32_t)get_u16(p + 2) << 16); }

size_t img_header_pack(const img_header_t* h, uint8_t* out);           // returns IMG_HDR_V2_LEN
size_t audio_header_pack(const audio_header_t* h, uint8_t* out);       // returns AUDIO_HDR_LEN
int audio_header_unpack(const uint8_t* in, size_t len, audio_header_t* h);  // 0 ok, -1 short
size_t caps_pack(const caps_t* c, uint8_t* out);                       // returns CAPS_LEN
/** Writes the 5-byte event prefix; returns EVT_PREFIX_LEN. */
size_t evt_prefix(uint8_t* out, uint8_t type, uint32_t board_ms);

/** True if `seq` is before `allowed` in 16-bit wrap-around order (credit check). */
static inline int seq_before(uint16_t seq, uint16_t allowed) { return (int16_t)(seq - allowed) < 0; }

#ifdef __cplusplus
}
#endif
