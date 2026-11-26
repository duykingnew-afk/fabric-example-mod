#!/usr/bin/env python3
"""CI helper: reads the "curve" lines of a smoke test log (POCKETFSR_SMOKETEST_CURVE) and prints, for every shot,
how the brightness of the whole picture develops frame by frame after a change, and how long a frame takes.

  python ci/curve_report.py prod/realpack/logs/latest.log

A shader pack that adapts to the picture (exposure, history) needs a while after the resolution changes; the
brightness of the last frames is what the shot shows. The time per frame is only a rough guide: the CI machine
draws everything on the CPU, so it is a worst case for the pixel work, not a phone's speed.
"""
import re
import sys

LINE = re.compile(r"curve (\S+) frame (\d+) ms (\d+) luma ([\d.]+)")
MARKS = (1, 3, 6, 10, 20, 40)


def main():
    shots = {}
    order = []
    with open(sys.argv[1], encoding="utf-8", errors="replace") as log:
        for line in log:
            match = LINE.search(line)
            if not match:
                continue
            name, frame, ms, luma = match.group(1), int(match.group(2)), int(match.group(3)), float(match.group(4))
            if name not in shots:
                shots[name] = []
                order.append(name)
            shots[name].append((frame, ms, luma))
    if not shots:
        print("no curve lines in", sys.argv[1])
        return
    header = f"{'shot':<22} {'frames':>6} " + " ".join(f"{'@' + str(m):>7}" for m in MARKS) + f" {'last':>7} {'ms/frame':>9}"
    print(header)
    for name in order:
        samples = shots[name]
        by_frame = {frame: luma for frame, _, luma in samples}
        last_frame, last_ms, last_luma = samples[-1]
        steady = [s for s in samples if s[0] >= 5]
        per_frame = "-"
        if len(steady) >= 2 and steady[-1][0] > steady[0][0]:
            per_frame = f"{(steady[-1][1] - steady[0][1]) / (steady[-1][0] - steady[0][0]):.0f}"
        cells = []
        for mark in MARKS:
            near = [f for f in by_frame if f >= mark]
            cells.append(f"{by_frame[min(near)]:7.1f}" if near else f"{'-':>7}")
        print(f"{name:<22} {last_frame:6d} " + " ".join(cells) + f" {last_luma:7.1f} {per_frame:>9}")


if __name__ == "__main__":
    main()
