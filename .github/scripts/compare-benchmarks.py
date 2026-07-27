#!/usr/bin/env python3
"""Compare two JMH JSON result files, print a Markdown table.

Used by benchmark-lto.yml to summarize LTO's throughput impact per platform:
compare-benchmarks.py <baseline.json> <variant.json>
"""
import json
import sys

# Windows' default console code page isn't UTF-8, so stdout would otherwise
# mangle the '±' in the table below (garbles to '?').
sys.stdout.reconfigure(encoding="utf-8")


def load(path):
    with open(path) as f:
        results = json.load(f)
    return {
        (r["benchmark"], tuple(sorted(r.get("params", {}).items()))): r
        for r in results
    }


def main():
    if len(sys.argv) != 3:
        print("usage: compare-benchmarks.py <baseline.json> <variant.json>", file=sys.stderr)
        return 1

    baseline = load(sys.argv[1])
    variant = load(sys.argv[2])

    print("| Benchmark | Params | Baseline | Variant | Delta |")
    print("|---|---|---:|---:|---:|")
    for key in sorted(baseline):
        if key not in variant:
            continue
        b, v = baseline[key]["primaryMetric"], variant[key]["primaryMetric"]
        # scoreError is "NaN" (a JSON string, not a number) when JMH can't
        # compute a confidence interval - e.g. a single measurement iteration.
        b_score, b_err, unit = b["score"], float(b["scoreError"]), b["scoreUnit"]
        v_score, v_err = v["score"], float(v["scoreError"])
        delta = (v_score - b_score) / b_score * 100 if b_score else 0.0
        name = key[0].rsplit(".", 1)[-1]
        params = ", ".join(f"{k}={pv}" for k, pv in key[1]) or "-"
        print(
            f"| {name} | {params} | {b_score:.3f} ± {b_err:.3f} {unit} "
            f"| {v_score:.3f} ± {v_err:.3f} {unit} | {delta:+.1f}% |"
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
