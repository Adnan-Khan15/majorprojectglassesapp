#include "glasses_frames.h"

size_t img_header_pack(const img_header_t* h, uint8_t* o) {
    o[0] = h->version;
    o[1] = h->flags;
    put_u16(o + 2, h->image_id);
    put_u32(o + 4, h->size);
    put_u32(o + 8, h->crc32);
    put_u16(o + 12, h->width);
    put_u16(o + 14, h->height);
    return IMG_HDR_V2_LEN;
}

size_t audio_header_pack(const audio_header_t* h, uint8_t* o) {
    o[0] = h->stream;
    o[1] = h->flags;
    put_u16(o + 2, h->seq);
    put_u16(o + 4, (uint16_t)h->predictor);
    o[6] = h->step_index;
    o[7] = h->codec;
    return AUDIO_HDR_LEN;
}

int audio_header_unpack(const uint8_t* in, size_t len, audio_header_t* h) {
    if (len < AUDIO_HDR_LEN) return -1;
    h->stream = in[0];
    h->flags = in[1];
    h->seq = get_u16(in + 2);
    h->predictor = (int16_t)get_u16(in + 4);
    h->step_index = in[6] > 88 ? 88 : in[6];
    h->codec = in[7];
    return 0;
}

size_t caps_pack(const caps_t* c, uint8_t* o) {
    o[0] = c->protocol;
    o[1] = c->fw_major; o[2] = c->fw_minor; o[3] = c->fw_patch;
    put_u32(o + 4, c->features);
    put_u16(o + 8, c->audio_rate);
    put_u16(o + 10, c->samples_per_frame);
    o[12] = c->codec;
    o[13] = c->tof_type;
    o[14] = c->reset_reason;
    o[15] = c->volume;
    o[16] = c->ceiling;
    put_u16(o + 17, c->hold_ms);
    put_u16(o + 19, c->ring_frames);
    put_u16(o + 21, c->prebuffer_frames);
    o[23] = c->tof_samples;
    o[24] = 0;  // reserved
    return CAPS_LEN;
}

size_t evt_prefix(uint8_t* o, uint8_t type, uint32_t board_ms) {
    o[0] = type;
    put_u32(o + 1, board_ms);
    return EVT_PREFIX_LEN;
}
