#!/usr/bin/env python3
"""CI helper: how far does each upscaled picture stay from the native one? (see SmokeTest.COMPARE_SHOTS)

  python ci/scale_report.py prod/sodium/smoketest sodium- out/sodium-sheet.png

Prints, for every picture of the run, how bright it is and how close it is to the native picture (PSNR, higher is
closer; "detail" is the share of the native picture's fine-detail energy it keeps: low = blurry, above 100 = noise
or halos added), and writes one picture with the same crop of every shot side by side, to be judged by eye.
The numbers are crude (clouds drift, a shader pack adds its own noise) but they order the shots correctly.
"""
import math
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageStat

SHOTS = [
    ("c-native", "native 100%"),
    ("c-fsr70", "FSR 70%"),
    ("c-fsr50", "FSR 50%"),
    ("c-fsr25", "FSR 25%"),
    ("c-fsr25-sharp", "FSR 25%, sharpness 100%"),
    ("c-fsr25-lod", "FSR 25%, textures 2 levels finer"),
    ("c-fsr50-lod", "FSR 50%, textures 1 level finer"),
    ("c-bilinear25", "bilinear 25%"),
    ("c-bilinear70", "bilinear 70%"),
    ("c-native-2", "native 100% again"),
]
CROP = (280, 270, 920, 630)  # the pig with its outline, the stone block, the water, the chest and the grass


def detail(image):
    luma = image.convert("L")
    dx = ImageStat.Stat(ImageChops.difference(luma, ImageChops.offset(luma, 1, 0))).mean[0]
    dy = ImageStat.Stat(ImageChops.difference(luma, ImageChops.offset(luma, 0, 1))).mean[0]
    return dx + dy


def psnr(a, b):
    stat = ImageStat.Stat(ImageChops.difference(a, b))
    mse = sum(stat.sum2) / (3.0 * a.width * a.height)
    return 99.0 if mse == 0 else 10.0 * math.log10(255.0 * 255.0 / mse)


def main():
    directory, prefix, sheet_path = sys.argv[1], sys.argv[2], sys.argv[3]
    pictures = {}
    for key, label in SHOTS:
        path = os.path.join(directory, f"{prefix}{key}.png")
        if os.path.exists(path):
            pictures[key] = Image.open(path).convert("RGB")
    if "c-native" not in pictures:
        print("no native picture in", directory)
        return
    native = pictures["c-native"]
    native_detail = detail(native)
    native_luma = sum(ImageStat.Stat(native).mean) / 3.0
    print(f"{'picture':<28} {'bright':>7} {'PSNR dB':>8} {'detail %':>9}   (against the native picture)")
    for key, label in SHOTS:
        image = pictures.get(key)
        if image is None:
            print(f"{label:<28} missing")
            continue
        luma = sum(ImageStat.Stat(image).mean) / 3.0
        print(f"{label:<28} {luma / native_luma:7.3f} {psnr(image, native):8.2f} {100.0 * detail(image) / native_detail:9.1f}")

    shown = [(key, label) for key, label in SHOTS if key in pictures and key not in ("c-native-2", "c-bilinear70", "c-fsr50-lod")]
    columns = 3
    rows = (len(shown) + columns - 1) // columns
    tile_w, tile_h = CROP[2] - CROP[0], CROP[3] - CROP[1]
    strip = 26
    sheet = Image.new("RGB", (columns * tile_w, rows * (tile_h + strip)), (20, 20, 20))
    draw = ImageDraw.Draw(sheet)
    for index, (key, label) in enumerate(shown):
        x = (index % columns) * tile_w
        y = (index // columns) * (tile_h + strip)
        sheet.paste(pictures[key].crop(CROP), (x, y + strip))
        draw.text((x + 8, y + 7), label, fill=(255, 255, 255))
    sheet.save(sheet_path)
    print("sheet:", sheet_path, sheet.size)


if __name__ == "__main__":
    main()
