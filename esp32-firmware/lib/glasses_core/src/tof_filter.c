#include "tof_filter.h"

tof_sample_t tof_classify(uint16_t raw_mm, int timed_out) {
    tof_sample_t s = {raw_mm, TOF_RAW_OK};
    if (timed_out || raw_mm == 65535) s.status = TOF_RAW_TIMEOUT;
    else if (raw_mm < TOF_MIN_MM || raw_mm > TOF_MAX_MM) s.status = TOF_RAW_OUT_OF_RANGE;
    return s;
}

tof_result_t tof_reduce(tof_sample_t* samples, size_t n, size_t min_valid) {
    tof_result_t r = {0, 0, 0, (uint8_t)(n > 255 ? 255 : n), 0};
    uint16_t valid[32];
    size_t k = 0;
    for (size_t i = 0; i < n; i++) {
        if (samples[i].status == TOF_RAW_TIMEOUT) r.flags |= TOF_FLAG_TIMEOUT;
        else if (samples[i].status == TOF_RAW_OUT_OF_RANGE) r.flags |= TOF_FLAG_OUT_OF_RANGE;
        else if (k < 32) valid[k++] = samples[i].mm;
    }
    r.valid_count = (uint8_t)k;
    if (k == 0 || k < min_valid) return r;
    // insertion sort: n is tiny (5-7)
    for (size_t i = 1; i < k; i++) {
        uint16_t v = valid[i];
        size_t j = i;
        while (j > 0 && valid[j - 1] > v) { valid[j] = valid[j - 1]; j--; }
        valid[j] = v;
    }
    r.median_mm = (k & 1) ? valid[k / 2] : (uint16_t)(((uint32_t)valid[k / 2 - 1] + valid[k / 2]) / 2);
    r.spread_mm = (uint16_t)(valid[k - 1] - valid[0]);
    r.flags |= TOF_FLAG_VALID;
    return r;
}
