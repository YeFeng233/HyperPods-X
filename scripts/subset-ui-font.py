"""Subset a local regular SF Pro font, retaining Glasmio's exact symbol catalog."""
from pathlib import Path
import argparse
import re


# CJK text uses Android fallback; SF Pro itself has no Han, Kana or Hangul glyphs.
TEXT_RANGES = (
    (0x0000, 0x024F), (0x0370, 0x052F), (0x1E00, 0x1EFF),
    (0x2000, 0x206F), (0x2070, 0x209F), (0x20A0, 0x20CF),
    (0x2100, 0x214F), (0x2190, 0x21FF), (0x2200, 0x22FF),
    (0x2300, 0x23FF), (0x25A0, 0x25FF), (0x2600, 0x26FF),
    (0x2700, 0x27BF), (0x2B00, 0x2BFF), (0x3000, 0x303F),
    (0xF6D5, 0xF8FF), (0xFB00, 0xFB06),
)


def required_codepoints(library):
    points = {0xFFFD}
    for first, last in TEXT_RANGES:
        points.update(range(first, last + 1))
    icons = library / "glasmio-icons/src/main/java/io/github/neoruaa/glasmio/icons"
    symbols = set()
    for name in ("SfSymbol.kt", "SfSymbolCatalog.kt"):
        text = (icons / name).read_text(encoding="utf-8")
        symbols.update(int(value, 16) for value in re.findall(r"0x([0-9A-Fa-f]{5,6})\b", text))
    symbols = {value for value in symbols if 0x100000 <= value <= 0x10FFFD}
    if not symbols:
        raise ValueError("No SF Symbols found in the pinned Glasmio catalog")
    return points | symbols


def subset_font(source, output, library):
    try:
        from fontTools.subset import Options, Subsetter
        from fontTools.ttLib import TTFont
    except ImportError:
        raise SystemExit("Install font tools first: python -m pip install -r scripts/requirements-fonts.txt")
    if source.resolve() == output.resolve():
        raise ValueError("The subset output must not overwrite the original font")
    points = required_codepoints(library)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".tmp.ttf")
    with TTFont(source, recalcTimestamp=False) as font:
        expected = points.intersection(font.getBestCmap())
        options = Options()
        options.layout_features = ["*"]
        options.drop_tables = ["MERG", "meta", "trak"]
        options.recalc_average_width = True
        subset = Subsetter(options=options)
        subset.populate(unicodes=points)
        subset.subset(font)
        font.save(temporary)
    with TTFont(temporary) as font:
        if not expected.issubset(font.getBestCmap()):
            raise ValueError("The subset lost required text or symbol glyphs")
    temporary.replace(output)
    print(f"Regular font: {source.stat().st_size:,} -> {output.stat().st_size:,} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--glasmio", type=Path, default=Path(".reference/Glasmio"))
    args = parser.parse_args()
    subset_font(args.source, args.output, args.glasmio)
