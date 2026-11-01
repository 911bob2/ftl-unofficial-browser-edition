#!/usr/bin/env python3
"""Turns the icon archive's Android sets into the app's launcher icon resources, keeping only what the app uses.

    python -I tools/android-icons/import.py <extracted ftl-icons/android> android/app/src/main/res

The app's minimum is Android 8, so every device takes the adaptive icon: the legacy PNGs, round copies and Play Store
images stay out. The foregrounds are fully opaque, so lossy WebP takes them from about 560 KB to about 40 KB an icon.

The sets' foregrounds show the middle two thirds of the art (the part inside a launcher's mask), which cuts the letters of
number 02. REFRAMED sets are made from the whole art instead, fitted to the mask, as in the archive's own preview sheet.
"""
import pathlib
import sys

from PIL import Image, ImageOps

SETS = ["01_kestrel_hero", "02_logo_planet", "05_shield_impact", "10_engines", "11_ftl_jump",
        "15_asteroid_field", "16_nebula_storm", "17_sun_flare", "20_kestrel_rear"]
DENSITIES = ["hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]
QUALITY = 92
REFRAMED = {"02_logo_planet"}
VISIBLE = 72 / 108


def reframed(art: pathlib.Path, size: int) -> Image.Image:
    """The whole art inside the visible middle; around it, the art's own edge mirrored, for launchers that move the layer."""
    inner = round(size * VISIBLE)
    whole = Image.open(art).convert("RGB").resize((inner, inner), Image.LANCZOS)
    tiled = Image.new("RGB", (inner * 3, inner * 3))
    for row in range(3):
        for col in range(3):
            tile = whole
            if col != 1:
                tile = ImageOps.mirror(tile)
            if row != 1:
                tile = ImageOps.flip(tile)
            tiled.paste(tile, (col * inner, row * inner))
    start = (inner * 3 - size) // 2
    return tiled.crop((start, start, start + size, start + size))


def main(source: pathlib.Path, res: pathlib.Path) -> None:
    colours = []
    (res / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    for name in SETS:
        number = name[:2]
        for density in DENSITIES:
            folder = res / f"mipmap-{density}"
            folder.mkdir(parents=True, exist_ok=True)
            image = Image.open(source / name / "res" / f"mipmap-{density}" / "ic_launcher_foreground.png").convert("RGB")
            if name in REFRAMED:
                image = reframed(source.parent / "png" / f"{name}.png", image.width)
            image.save(folder / f"ic_launcher_{number}_fg.webp", "WEBP", quality=QUALITY, method=6)
        colour = (source / name / "res" / "values" / "ic_launcher_background.xml").read_text()
        colour = colour.split('name="ic_launcher_background">')[1].split("<")[0].strip()
        colours.append(f'    <color name="ic_launcher_{number}_bg">{colour}</color>\n')
        (res / "mipmap-anydpi-v26" / f"ic_launcher_{number}.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@color/ic_launcher_{number}_bg" />\n'
            f'    <foreground android:drawable="@mipmap/ic_launcher_{number}_fg" />\n'
            '</adaptive-icon>\n', newline="\n")
    (res / "values" / "ic_launcher_colors.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + "".join(colours) + "</resources>\n", newline="\n")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
