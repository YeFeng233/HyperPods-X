"""Package only committed source and a verified, signed device-test build."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--signature-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("dist/github-release"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", args.tag):
        raise ValueError("Invalid release tag")
    version_file = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', version_file).group(1)
    if args.tag != "v" + version.removesuffix("-dev"):
        raise ValueError("Release tag does not match the app version")
    if not (root / "docs/releases" / f"{args.tag}.md").is_file():
        raise ValueError("Release notes are missing")

    output = (root / args.output).resolve()
    output.relative_to((root / "dist").resolve())
    if output == (root / "dist").resolve():
        raise ValueError("Use a dedicated subdirectory inside dist")
    output.mkdir(parents=True, exist_ok=True)

    expected = (root / ".github/signing-certificate.sha256").read_text().strip()
    signature = args.signature_report.read_text(encoding="utf-8")
    certificates = set(re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signature))
    if {value.lower() for value in certificates} != {expected}:
        raise ValueError("APK signing certificate does not match this project")

    metadata = json.loads((root / "app/build/outputs/apk/deviceTest/output-metadata.json").read_text())
    if metadata["applicationId"] != "com.hyper.podsrx" or metadata["variantName"] != "deviceTest":
        raise ValueError("Unexpected APK identity or build variant")
    if len(metadata["elements"]) != 1 or metadata["elements"][0]["versionName"] != version + "-device-test":
        raise ValueError("APK version does not match the source")

    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for results in (root / "app/build/test-results/testDebugUnitTest", root / "core/protocol/build/test-results/test"):
        suites = list(results.glob("TEST-*.xml"))
        if not suites:
            raise ValueError(f"Test reports missing: {results.relative_to(root)}")
        for path in suites:
            suite = ET.parse(path).getroot()
            for field in totals:
                totals[field] += int(suite.get(field, "0"))
    if not totals["tests"] or totals["failures"] or totals["errors"]:
        raise ValueError("Unit tests have not passed")
    issues = ET.parse(root / "app/build/reports/lint-results-debug.xml").getroot()
    lint = dict(errors=0, warnings=0)
    for issue in issues.findall("issue"):
        if issue.get("severity") in ("Error", "Fatal"):
            lint["errors"] += 1
        elif issue.get("severity") == "Warning":
            lint["warnings"] += 1
    if lint["errors"]:
        raise ValueError("Android lint has errors")

    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    tracked = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", "-z", "HEAD"], cwd=root).decode().split("\0")
    forbidden = (".device/", ".tooling/", ".reference/", ".gradle/", ".kotlin/", "dist/")
    for name in filter(None, tracked):
        parts = Path(name).parts
        if name.startswith(forbidden) or "build" in parts or name == "local.properties" or Path(name).name.startswith(".env"):
            raise ValueError(f"Local-only data is tracked: {name}")
        if Path(name).suffix.lower() in {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".apk"}:
            raise ValueError(f"Signing material or a binary build is tracked: {name}")

    apk = output / f"HyperPods-X-{args.tag}-device-test.apk"
    shutil.copyfile(root / "app/build/outputs/apk/deviceTest/app-deviceTest.apk", apk)
    source = output / f"HyperPods-X-{args.tag}-source.zip"
    subprocess.run(["git", "archive", "--format=zip", "--prefix=HyperPods-X/", f"--output={source}", commit], cwd=root, check=True)
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None:
            raise ValueError("Source archive failed integrity verification")
    info = {
        "tag": args.tag,
        "source_commit": commit,
        "application_id": metadata["applicationId"],
        "version_name": metadata["elements"][0]["versionName"],
        "version_code": metadata["elements"][0]["versionCode"],
        "variant": metadata["variantName"],
        "signing_certificate_sha256": expected,
        "unit_tests": totals,
        "lint": lint,
    }
    if os.environ.get("GITHUB_RUN_ID"):
        info["workflow_run_url"] = f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    build_info = output / "build-info.json"
    build_info.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    assets = sorted((apk, source, build_info))
    checksums = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in assets)
    (output / "SHA256SUMS").write_text(checksums, encoding="ascii")
    print(json.dumps({"commit": commit, "tag": args.tag, "tests": totals, "files": [path.name for path in assets] + ["SHA256SUMS"]}, indent=2))


if __name__ == "__main__":
    main()
