"""Prepare the pinned Glasmio source and optional host-owned SF Pro build inputs."""
from pathlib import Path
import argparse
import hashlib
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
GLASMIO_COMMIT = "bbba63b2f2584c4819bd88043332e241820d1ca0"
FONT_TAG = "v27.0.1789118100"
FONTS = {
    "SF-Pro.ttf": "7270e1593be7b88597d8ab302e4d156a33084c85b8161e042ff6510cdaf10e10",
}


def git(*args):
    return subprocess.check_output(["git", *map(str, args)], text=True).strip()


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def prepare(with_fonts):
    library = ROOT / ".reference/Glasmio"
    if not library.exists():
        library.parent.mkdir(parents=True, exist_ok=True)
        git("clone", "--no-checkout", "https://github.com/NEORUAA/Glasmio.git", library)
        git("-C", library, "checkout", "--detach", GLASMIO_COMMIT)
    if git("-C", library, "rev-parse", "HEAD") != GLASMIO_COMMIT:
        raise SystemExit("Existing Glasmio checkout has a different revision; preserve or move it before preparing the pinned version.")
    print("Glasmio " + GLASMIO_COMMIT)
    properties = ROOT / "local.properties"
    text = properties.read_text(encoding="utf-8") if properties.exists() else ""
    sdk = next((line for line in text.splitlines() if line.startswith("sdk.dir=")), None)
    library_properties = library / "local.properties"
    if sdk and not library_properties.exists():
        library_properties.write_text(sdk + "\n", encoding="utf-8")
    if with_fonts:
        destination = ROOT / ".tooling/fonts"
        destination.mkdir(parents=True, exist_ok=True)
        for name, expected in FONTS.items():
            output = destination / name
            if not output.exists():
                temporary = destination / (name + ".download")
                url = f"https://raw.githubusercontent.com/bradleyhodges/SFWindows/{FONT_TAG}/SF%20Pro/{name}"
                urllib.request.urlretrieve(url, temporary)
                if sha256(temporary) != expected:
                    raise SystemExit("Font checksum mismatch: " + name)
                temporary.replace(output)
            if sha256(output) != expected:
                raise SystemExit("Existing font differs from the pinned SFWindows version: " + name)
            print("Verified " + name)
        subset = destination / "SF-Pro-Subset.ttf"
        subprocess.run([sys.executable, str(ROOT / "scripts/subset-ui-font.py"),
                        str(destination / "SF-Pro.ttf"), str(subset), "--glasmio", str(library)], check=True)
        text = "\n".join(line for line in text.splitlines()
                         if not line.startswith(("glasmio.symbolFont=", "glasmio.italicFont=")))
        text = text.rstrip() + "\nglasmio.symbolFont=" + subset.as_posix() + "\n"
        properties.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fonts", action="store_true", help="Download and verify the user-selected SFWindows fonts as local APK inputs.")
    prepare(parser.parse_args().fonts)
