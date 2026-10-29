"""Check that R8 kept the LSPosed module entry and metadata in a built APK."""

import argparse
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = root / "hook/src/main/resources/META-INF/xposed"

    with zipfile.ZipFile(args.apk) as apk:
        names = set(apk.namelist())
        for expected in sorted(source.iterdir()):
            entry = "META-INF/xposed/" + expected.name
            if entry not in names:
                raise ValueError(f"Module metadata missing from the APK: {entry}")
            packed = apk.read(entry).decode("utf-8").split()
            if packed != expected.read_text(encoding="utf-8").split():
                raise ValueError(f"Module metadata differs from the source: {entry}")
        dex = [apk.read(name) for name in sorted(names) if name.startswith("classes") and name.endswith(".dex")]

    if not dex:
        raise ValueError("No dex files in the APK")
    for entry_class in (source / "java_init.list").read_text(encoding="utf-8").split():
        descriptor = ("L" + entry_class.replace(".", "/") + ";").encode()
        if not any(descriptor in data for data in dex):
            raise ValueError(f"Module entry class was removed or renamed: {entry_class}")
        print("Kept " + entry_class)
    print("Scopes: " + ", ".join((source / "scope.list").read_text(encoding="utf-8").split()))


if __name__ == "__main__":
    main()
