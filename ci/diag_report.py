#!/usr/bin/env python3
"""CI helper: brightness report of a diagnostic run (see SmokeTest.DIAG_SHOTS).

  python ci/diag_report.py prod/realdiag/smoketest realdiag-

Prints, for every picture, the mean of each colour channel, a few luminance percentiles and the share of nearly
black pixels, then how bright each upscaled picture is compared with the native one (the final pictures), and how
bright the low resolution picture and the EASU result of each frame were compared with the plain stretch.
A picture that is a lot darker than its reference points at the pass that made it.
"""
import glob
import os
import sys

from PIL import Image, ImageStat


def percentile(hist, total, fraction):
    target = total * fraction
    seen = 0
    for value, count in enumerate(hist):
        seen += count
        if seen >= target:
            return value
    return 255


def load(directory, prefix):
    pictures = {}
    for path in sorted(glob.glob(os.path.join(directory, f"{prefix}*.png"))):
        name = os.path.basename(path)[len(prefix):-len(".png")]
        image = Image.open(path).convert("RGB")
        mean = ImageStat.Stat(image).mean
        hist = image.convert("L").histogram()
        total = sum(hist)
        pictures[name] = {
            "size": f"{image.width}x{image.height}",
            "mean": mean,
            "luma": sum(mean) / 3.0,
            "p5": percentile(hist, total, 0.05),
            "p50": percentile(hist, total, 0.50),
            "p95": percentile(hist, total, 0.95),
            "dark": 100.0 * sum(hist[:9]) / total,
        }
    return pictures


def ratio(pictures, name, reference):
    if name in pictures and reference in pictures and pictures[reference]["luma"] > 0:
        return f"{pictures[name]['luma'] / pictures[reference]['luma']:.3f}"
    return "-"


def main():
    directory = sys.argv[1]
    prefix = sys.argv[2] if len(sys.argv) > 2 else ""
    pictures = load(directory, prefix)
    if not pictures:
        print("no pictures found in", directory, "with prefix", repr(prefix))
        return
    print(f"{'picture':<28} {'size':>9} {'mean R':>7} {'mean G':>7} {'mean B':>7} {'p5':>4} {'p50':>4} {'p95':>4} {'dark%':>6}")
    for name, p in pictures.items():
        r, g, b = p["mean"]
        print(f"{name:<28} {p['size']:>9} {r:7.1f} {g:7.1f} {b:7.1f} {p['p5']:4d} {p['p50']:4d} {p['p95']:4d} {p['dark']:6.2f}")

    print()
    print("final picture / native picture (1.000 = same brightness)")
    for name in pictures:
        if name.startswith("d-") and not name.startswith(("low-", "mid-")) and name not in ("d-native", "d-native-2"):
            print(f"  {name:<24} {ratio(pictures, name, 'd-native')}")
    print(f"  {'d-native-2':<24} {ratio(pictures, 'd-native-2', 'd-native')}   (native again: the scene itself must not drift)")

    print()
    print("low resolution picture of the frame / the one of the plain stretch (same world, so it must be 1.000)")
    for name in pictures:
        if name.startswith("low-"):
            print(f"  {name:<28} {ratio(pictures, name, 'low-d-bilinear')}")

    print()
    print("EASU result (scratch target) / low resolution picture of the same frame (EASU alone must keep the brightness)")
    for name in pictures:
        if name.startswith("mid-"):
            print(f"  {name:<28} {ratio(pictures, name, 'low-' + name[len('mid-'):])}")


if __name__ == "__main__":
    main()
