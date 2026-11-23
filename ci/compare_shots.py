#!/usr/bin/env python3
"""CI helper: compares the screenshots of one smoke test run.

  python ci/compare_shots.py prod/iris/smoketest iris-            (exit 1 if a pair is too different)
  python ci/compare_shots.py prod/iris/smoketest iris- --report-only

The same scene from the same spot must look the same however often the upscaling was switched on and off in
between, and an upscaled picture must stay close to the native one. Prints the mean absolute difference per
colour channel (0 = identical, 255 = opposite) and exits with 1 if a pair is further apart than allowed.
"""
import os
import sys

from PIL import Image, ImageChops, ImageStat

# (picture, picture it must match, largest allowed mean difference, why)
CHECKS = [
    ("native-again", "native", 1.5, "native again after upscaling"),
    ("native-last", "native", 1.5, "native again after upscaling (2nd time)"),
    ("fsr67-again", "fsr67", 1.5, "67% again after being off"),
    ("fsr67-last", "fsr67", 1.5, "67% again after 50%"),
    ("fsr50-last", "fsr50", 1.5, "50% again after being off"),
    ("fsr67", "native", 4.0, "upscaled 67% vs native"),
    ("fsr50", "native", 4.0, "upscaled 50% vs native"),
    ("fsr67-sky", "native-sky", 4.0, "upscaled 67% vs native, looking up"),
]


def difference(path_a, path_b):
    a = Image.open(path_a).convert("RGB")
    b = Image.open(path_b).convert("RGB")
    if a.size != b.size:
        return None
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3.0


def main():
    arguments = [a for a in sys.argv[1:] if not a.startswith("--")]
    report_only = "--report-only" in sys.argv
    directory, prefix = arguments[0], arguments[1] if len(arguments) > 1 else ""
    bad = 0
    print(f"{'picture':<14} {'compared with':<14} {'difference':>10} {'allowed':>8}  what")
    for name, reference, allowed, what in CHECKS:
        path_a = os.path.join(directory, f"{prefix}{name}.png")
        path_b = os.path.join(directory, f"{prefix}{reference}.png")
        if not (os.path.exists(path_a) and os.path.exists(path_b)):
            print(f"{name:<14} {reference:<14} {'missing':>10} {allowed:>8}  {what}")
            bad += 1
            continue
        value = difference(path_a, path_b)
        verdict = "ok"
        if value is None:
            verdict = "SIZE DIFFERS"
            bad += 1
            shown = "-"
        else:
            shown = f"{value:.2f}"
            if value > allowed:
                verdict = "TOO DIFFERENT"
                bad += 1
        print(f"{name:<14} {reference:<14} {shown:>10} {allowed:>8}  {what}  [{verdict}]")
    sys.exit(0 if report_only else (1 if bad else 0))


if __name__ == "__main__":
    main()
