#!/usr/bin/env python3
"""p50/p90 per stage from the app's latency CSV (Diagnostics -> Export latency log,
or `adb pull /sdcard/Android/data/com.smartglasses.app/files/latency/latency.csv`).

Usage:  scripts/latency_report.py latency.csv [--mode tap|question|phone_question] [--source glasses|gallery|phone_mic]

For every pair of consecutive stages that both appear in a row, prints the
duration distribution, plus end-to-end from the first recorded stage to
play_started. Only rows with outcome "ok" are used. Warns below 10 runs.
"""
import argparse
import csv
import math
import sys

STAGES = [
    "button_down", "record_start", "button_up", "photo_captured", "image_header",
    "image_received", "audio_received", "tof_received", "transcript_ready", "ocr_done",
    "first_model_output", "answer_complete", "tts_ready", "play_started",
]


def percentile(values, p):
    """Nearest-rank percentile (no interpolation), p in 0..100."""
    if not values:
        return None
    s = sorted(values)
    k = max(0, min(len(s) - 1, math.ceil(p / 100.0 * len(s)) - 1))
    return s[k]


def load(path, mode, source):
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    out = []
    for r in rows:
        if r.get("outcome") != "ok":
            continue
        if mode and r.get("mode") != mode:
            continue
        if source and r.get("source") != source:
            continue
        out.append({s: int(r[s + "_ms"]) for s in STAGES if r.get(s + "_ms", "") != ""})
    return out, len(rows)


def report(runs):
    lines = []
    pairs = {}
    for run in runs:
        present = [s for s in STAGES if s in run]
        for a, b in zip(present, present[1:]):
            pairs.setdefault((a, b), []).append(run[b] - run[a])
        if "play_started" in run:
            pairs.setdefault(("(first stage)", "play_started"), []).append(run["play_started"] - min(run.values()))
    order = {s: i for i, s in enumerate(STAGES)}
    keys = sorted(pairs, key=lambda k: (k[0] == "(first stage)", order.get(k[0], -1), order.get(k[1], -1)))
    lines.append(f"{'stage':<45} {'n':>4} {'p50 ms':>8} {'p90 ms':>8} {'min':>7} {'max':>7}")
    for k in keys:
        v = pairs[k]
        lines.append(f"{k[0] + ' -> ' + k[1]:<45} {len(v):>4} {percentile(v, 50):>8} {percentile(v, 90):>8} {min(v):>7} {max(v):>7}")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv")
    ap.add_argument("--mode")
    ap.add_argument("--source")
    a = ap.parse_args()
    runs, total = load(a.csv, a.mode, a.source)
    print(f"{len(runs)} successful runs used (of {total} rows in the file)")
    if len(runs) < 10:
        print("WARNING: fewer than 10 runs; percentiles are not meaningful yet", file=sys.stderr)
    if runs:
        print(report(runs))


if __name__ == "__main__":
    main()
