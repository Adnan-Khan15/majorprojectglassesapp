// Host tests for lib/glasses_core. Run: pio test -e native
#include <unity.h>
#include <string.h>
#include "crc32.h"
#include "ima_adpcm.h"
#include "tof_filter.h"
#include "adpcm_golden.h"

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

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_crc32_check_value);
    RUN_TEST(test_adpcm_matches_golden_vectors);
    RUN_TEST(test_adpcm_lost_block_does_not_corrupt_next);
    RUN_TEST(test_tof_median_spread_and_flags);
    RUN_TEST(test_tof_too_few_valid_is_invalid);
    RUN_TEST(test_tof_range_limits);
    return UNITY_END();
}
