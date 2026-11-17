// Host tests for lib/glasses_core. Run: pio test -e native
#include <unity.h>
#include <string.h>
#include "crc32.h"
#include "ima_adpcm.h"
#include "tof_filter.h"
#include "adpcm_golden.h"
#include "glasses_frames.h"

void setUp() {}
void tearDown() {}

static void test_crc32_check_value() {
    const char* s = "123456789";
    TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, crc32_update(0, (const uint8_t*)s, 9));
    // incremental == one-shot
    uint32_t c = crc32_update(0, (const uint8_t*)s, 4);
    TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, crc32_update(c, (const uint8_t*)s + 4, 5));
    TEST_ASSERT_EQUAL_HEX32(0, crc32_update(0, NULL, 0));
}

static void test_adpcm_matches_golden_vectors() {
    ima_state_t enc = {0, 0};
    for (int b = 0; b < GOLDEN_BLOCKS; b++) {
        TEST_ASSERT_EQUAL_INT16(GOLDEN_START_PRED[b], enc.predictor);
        TEST_ASSERT_EQUAL_UINT8(GOLDEN_START_INDEX[b], enc.step_index);
        uint8_t out[GOLDEN_SPB / 2];
        size_t n = ima_encode(&enc, &GOLDEN_PCM[b * GOLDEN_SPB], GOLDEN_SPB, out);
        TEST_ASSERT_EQUAL(GOLDEN_SPB / 2, n);
        TEST_ASSERT_EQUAL_UINT8_ARRAY(&GOLDEN_ADPCM[b * GOLDEN_SPB / 2], out, GOLDEN_SPB / 2);

        ima_state_t dec = {GOLDEN_START_PRED[b], GOLDEN_START_INDEX[b]};
        int16_t pcm[GOLDEN_SPB];
        TEST_ASSERT_EQUAL(GOLDEN_SPB, ima_decode(&dec, out, GOLDEN_SPB / 2, pcm));
        TEST_ASSERT_EQUAL_INT16_ARRAY(&GOLDEN_DECODED[b * GOLDEN_SPB], pcm, GOLDEN_SPB);
    }
}

static void test_adpcm_lost_block_does_not_corrupt_next() {
    // Each block carries its own start state, so a receiver that skips block 1
    // still decodes block 2 exactly.
    ima_state_t dec = {GOLDEN_START_PRED[2], GOLDEN_START_INDEX[2]};
    int16_t pcm[GOLDEN_SPB];
    ima_decode(&dec, &GOLDEN_ADPCM[2 * GOLDEN_SPB / 2], GOLDEN_SPB / 2, pcm);
    TEST_ASSERT_EQUAL_INT16_ARRAY(&GOLDEN_DECODED[2 * GOLDEN_SPB], pcm, GOLDEN_SPB);
}

static void test_tof_median_spread_and_flags() {
    tof_sample_t s[7] = {
        tof_classify(362, 0), tof_classify(370, 0), tof_classify(8190, 0), tof_classify(355, 0),
        tof_classify(65535, 0), tof_classify(366, 0), tof_classify(359, 1),
    };
    tof_result_t r = tof_reduce(s, 7, 3);
    TEST_ASSERT_TRUE(r.flags & TOF_FLAG_VALID);
    TEST_ASSERT_TRUE(r.flags & TOF_FLAG_OUT_OF_RANGE);
    TEST_ASSERT_TRUE(r.flags & TOF_FLAG_TIMEOUT);
    TEST_ASSERT_EQUAL_UINT8(4, r.valid_count);   // 362 370 355 366
    TEST_ASSERT_EQUAL_UINT8(7, r.sample_count);
    TEST_ASSERT_EQUAL_UINT16(364, r.median_mm);  // (362 + 366) / 2
    TEST_ASSERT_EQUAL_UINT16(15, r.spread_mm);   // 370 - 355
}

static void test_tof_too_few_valid_is_invalid() {
    tof_sample_t s[5] = {tof_classify(8191, 0), tof_classify(8191, 0), tof_classify(8191, 0),
                         tof_classify(500, 0), tof_classify(510, 0)};
    tof_result_t r = tof_reduce(s, 5, 3);
    TEST_ASSERT_FALSE(r.flags & TOF_FLAG_VALID);
    TEST_ASSERT_EQUAL_UINT16(0, r.median_mm);
    TEST_ASSERT_EQUAL_UINT8(2, r.valid_count);
    TEST_ASSERT_TRUE(r.flags & TOF_FLAG_OUT_OF_RANGE);
}

static void test_tof_range_limits() {
    TEST_ASSERT_EQUAL(TOF_RAW_OUT_OF_RANGE, tof_classify(20, 0).status);
    TEST_ASSERT_EQUAL(TOF_RAW_OK, tof_classify(30, 0).status);
    TEST_ASSERT_EQUAL(TOF_RAW_OK, tof_classify(2000, 0).status);
    TEST_ASSERT_EQUAL(TOF_RAW_OUT_OF_RANGE, tof_classify(2001, 0).status);
}


// The same byte strings are asserted in android-app FramesTest.kt.
static void test_image_header_v2_bytes() {
    img_header_t h = {2, IMG_FLAG_QUESTION | IMG_FLAG_RETRIED, 0x1234, 30000, 0xCBF43926u, 640, 480};
    uint8_t b[IMG_HDR_V2_LEN];
    TEST_ASSERT_EQUAL(16, img_header_pack(&h, b));
    const uint8_t want[] = {0x02, 0x03, 0x34, 0x12, 0x30, 0x75, 0x00, 0x00, 0x26, 0x39, 0xF4, 0xCB, 0x80, 0x02, 0xE0, 0x01};
    TEST_ASSERT_EQUAL_UINT8_ARRAY(want, b, 16);
}

static void test_audio_header_roundtrip_and_bytes() {
    audio_header_t h = {STREAM_SPEECH, AUDIO_FLAG_START, 0xFFFE, -1234, 37, AUDIO_CODEC_ADPCM};
    uint8_t b[AUDIO_HDR_LEN];
    audio_header_pack(&h, b);
    const uint8_t want[] = {0x02, 0x01, 0xFE, 0xFF, 0x2E, 0xFB, 0x25, 0x01};
    TEST_ASSERT_EQUAL_UINT8_ARRAY(want, b, 8);
    audio_header_t u;
    TEST_ASSERT_EQUAL(0, audio_header_unpack(b, 8, &u));
    TEST_ASSERT_EQUAL_INT16(-1234, u.predictor);
    TEST_ASSERT_EQUAL_UINT16(0xFFFE, u.seq);
    TEST_ASSERT_EQUAL(-1, audio_header_unpack(b, 7, &u));
}

static void test_caps_bytes() {
    caps_t c = {2, 0, 4, 0, FEAT_CAMERA | FEAT_SPEAKER | FEAT_MIC, 16000, 400, AUDIO_CODEC_ADPCM, TOF_TYPE_VL53L0X,
                9, 60, 50, 500, 80, 16, 5};
    uint8_t b[CAPS_LEN];
    TEST_ASSERT_EQUAL(25, caps_pack(&c, b));
    const uint8_t want[] = {2, 0, 4, 0, 0x0D, 0, 0, 0, 0x80, 0x3E, 0x90, 0x01, 1, 1, 9, 60, 50, 0xF4, 0x01, 80, 0, 16, 0, 5, 0};
    TEST_ASSERT_EQUAL_UINT8_ARRAY(want, b, 25);
}

static void test_seq_wraparound() {
    TEST_ASSERT_TRUE(seq_before(10, 20));
    TEST_ASSERT_FALSE(seq_before(20, 20));
    TEST_ASSERT_TRUE(seq_before(65530, 5));   // wrapped
    TEST_ASSERT_FALSE(seq_before(5, 65530));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_crc32_check_value);
    RUN_TEST(test_adpcm_matches_golden_vectors);
    RUN_TEST(test_adpcm_lost_block_does_not_corrupt_next);
    RUN_TEST(test_tof_median_spread_and_flags);
    RUN_TEST(test_tof_too_few_valid_is_invalid);
    RUN_TEST(test_tof_range_limits);
    RUN_TEST(test_image_header_v2_bytes);
    RUN_TEST(test_audio_header_roundtrip_and_bytes);
    RUN_TEST(test_caps_bytes);
    RUN_TEST(test_seq_wraparound);
    return UNITY_END();
}
