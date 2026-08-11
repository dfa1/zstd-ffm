#!/usr/bin/env python3
"""Format one JMH JSON result file into contestant-comparison tables.

Used by benchmark.yml to report zstd-java (byte[] and MemorySegment)
against zstd-jni and aircompressor on the same build, plus the cost of
creating a fresh native context per call versus reusing one across calls.

Usage:
  format-contestants.py <results.json>
"""
import json
import sys

# Windows' default console code page isn't UTF-8, so stdout would otherwise
# mangle the '±' in the tables below (garbles to '?').
sys.stdout.reconfigure(encoding="utf-8")

CONTESTANTS = ["zstdJavaSegment", "zstdJavaBytes", "zstdJni", "aircompressor"]
CONTEXT_PAIRS = [
    ("zstdJavaSegment", "zstdJavaSegmentFreshContext", "MemorySegment"),
    ("zstdJavaBytes", "zstdJavaBytesFreshContext", "byte[]"),
]


def load(path):
    with open(path) as f:
        results = json.load(f)

    by_class = {}
    for r in results:
        fqn = r["benchmark"]
        cls, method = fqn.rsplit(".", 1)
        cls = cls.rsplit(".", 1)[-1]
        size = r.get("params", {}).get("size")
        if size is None:
            continue
        by_class.setdefault(cls, {}).setdefault(size, {})[method] = r["primaryMetric"]
    return by_class


def fmt(metric):
    if metric is None:
        return "-"
    return f"{metric['score']:.3f} ± {float(metric['scoreError']):.3f} {metric['scoreUnit']}"


def print_contestants_table(cls, by_size):
    print(f"#### {cls}: contestants (ops/ms, higher is better)")
    print()
    print("| size | " + " | ".join(CONTESTANTS) + " |")
    print("|---|" + "---:|" * len(CONTESTANTS))
    for size in sorted(by_size, key=int):
        row = by_size[size]
        cells = [fmt(row.get(c)) for c in CONTESTANTS]
        print(f"| {int(size):,} | " + " | ".join(cells) + " |")
    print()


def print_context_table(cls, by_size):
    rows = []
    for size in sorted(by_size, key=int):
        row = by_size[size]
        for reused, fresh, label in CONTEXT_PAIRS:
            r, f = row.get(reused), row.get(fresh)
            if r is None or f is None:
                continue
            delta = (f["score"] - r["score"]) / r["score"] * 100 if r["score"] else 0.0
            rows.append((size, label, r, f, delta))

    if not rows:
        return

    print(f"#### {cls}: context reuse (reused across calls vs fresh per call)")
    print()
    print("| size | mode | reused context | fresh context per call | delta |")
    print("|---|---|---:|---:|---:|")
    for size, label, r, f, delta in rows:
        print(f"| {int(size):,} | {label} | {fmt(r)} | {fmt(f)} | {delta:+.1f}% |")
    print()


def main():
    if len(sys.argv) != 2:
        print("usage: format-contestants.py <results.json>", file=sys.stderr)
        return 1

    by_class = load(sys.argv[1])
    for cls in sorted(by_class):
        print_contestants_table(cls, by_class[cls])
        print_context_table(cls, by_class[cls])

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
