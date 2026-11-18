#!/usr/bin/env python3
"""Export the Lantern day/night artwork and its encoded dimensions (requires Pillow)."""

import argparse
import json
from pathlib import Path

from PIL import Image, ImageOps


DEFAULT_OUTPUT = (
    Path(__file__).resolve().parents[1]
    / "src/app/src/main/assets/reader-art/lantern-v1"
)
TIERS = {"wide": (768, 1152), "portrait": (432, 660)}


def portrait_crop(master):
    width = min(round(master.height * 0.75), master.width)
    left = min(max(round(master.width * 0.75 - width / 2), 0), master.width - width)
    return master.crop((left, 0, left + width, master.height))


def export_tiers(mode, family, crop, directory):
    variants = []
    widths = [width for width in TIERS[family] if width < crop.width] + [crop.width]
    for width in widths:
        height = max(1, round(crop.height * width / crop.width))
        image = crop if width == crop.width else crop.resize((width, height), Image.Resampling.LANCZOS)
        file = f"{mode}-{family}-{width}.webp"
        image.save(directory / file, format="WEBP", quality=74, method=6)
        with Image.open(directory / file) as encoded:
            variants.append({"file": file, "width": encoded.width, "height": encoded.height})
    return variants


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("day", type=Path)
    parser.add_argument("night", type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {}
    for mode, path in (("day", args.day), ("night", args.night)):
        with Image.open(path) as source:
            master = ImageOps.exif_transpose(source).convert("RGB")
        manifest[mode] = {
            "wide": export_tiers(mode, "wide", master, args.output),
            "portrait": export_tiers(mode, "portrait", portrait_crop(master), args.output),
        }
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
