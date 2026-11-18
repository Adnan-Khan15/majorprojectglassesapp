#!/usr/bin/env python3
"""Scores the STT spike (docs/STT_SPIKE.md).

  scripts/wer.py results.csv references.csv

results.csv:    written by the app (Diagnostics -> STT spike): file,engine,transcript,ms,audio_ms,error
references.csv: written by you:  file,reference[,condition]   e.g.  q01.wav,where are my house keys,quiet

Prints word error rate (WER) per engine (overall and per condition, e.g. quiet / noisy),
plus latency p50/p90. WER = (substitutions + deletions + insertions) / reference words.
"""
import csv
import math
import re
import sys
from collections import defaultdict


def norm(text):
    text = text.lower().replace("’", "'")
    text = re.sub(r"[^a-z0-9' ]+", " ", text)
    return text.split()


def edit_distance(ref, hyp):
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
            prev, d[j] = d[j], cur
    return d[len(hyp)]


def pct(values, p):
    s = sorted(values)
    return s[max(0, min(len(s) - 1, math.ceil(p / 100 * len(s)) - 1))] if s else None


def score(results_rows, refs):
    by = defaultdict(lambda: {"err": 0, "words": 0, "ms": [], "n": 0, "failed": 0, "cond": defaultdict(lambda: [0, 0])})
    for r in results_rows:
        ref = refs.get(r["file"])
        if ref is None:
            continue
        e = by[r["engine"]]
        e["n"] += 1
        if r.get("error"):
            e["failed"] += 1
        ref_words = norm(ref[0])
        errs = edit_distance(ref_words, norm(r.get("transcript", "")))
        e["err"] += errs
        e["words"] += len(ref_words)
        e["cond"][ref[1]][0] += errs
        e["cond"][ref[1]][1] += len(ref_words)
        if r.get("ms"):
            e["ms"].append(int(r["ms"]))
    return by


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    with open(sys.argv[1], newline="") as f:
        results = list(csv.DictReader(f))
    with open(sys.argv[2], newline="") as f:
        refs = {row["file"]: (row["reference"], row.get("condition") or "all") for row in csv.DictReader(f)}
    by = score(results, refs)
    if len(refs) < 20:
        print(f"WARNING: only {len(refs)} reference clips; the spike asks for at least 20", file=sys.stderr)
    print(f"{'engine':<20} {'clips':>5} {'WER':>7} {'p50 ms':>8} {'p90 ms':>8} {'failed':>6}  per condition")
    for name, e in sorted(by.items()):
        wer = e["err"] / e["words"] if e["words"] else float("nan")
        conds = ", ".join(f"{c}: {v[0] / v[1]:.1%}" for c, v in sorted(e["cond"].items()) if v[1])
        print(f"{name:<20} {e['n']:>5} {wer:>7.1%} {pct(e['ms'], 50) or '-':>8} {pct(e['ms'], 90) or '-':>8} {e['failed']:>6}  {conds}")


if __name__ == "__main__":
    main()
