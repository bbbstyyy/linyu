#!/usr/bin/env python3
"""Validate the shared version, tag, and (optionally) release APK without credentials."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def version(root=ROOT):
    values = {}
    for line in (root / "version.properties").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            key, value = line.split("=", 1)
            if key in values:
                raise ValueError("Duplicate version property")
            values[key] = value.strip()
    name = values["versionName"]
    if not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[a-z][a-z0-9.-]*)?", name):
        raise ValueError("versionName must be MAJOR.MINOR.PATCH with optional lowercase prerelease")
    code = int(values["versionCode"])
    if not 1 <= code <= 2100000000:
        raise ValueError("Invalid Android versionCode")
    return {"version": name, "code": code, "tag": "v" + name, "prerelease": "-" in name}


def check_tag(info, tag):
    if tag != info["tag"]:
        raise ValueError("Tag does not match version.properties: expected " + info["tag"])


def check_apk(info, apk, aapt):
    output = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
    fields = dict(re.findall(r"(\w+)='([^']*)'", output.splitlines()[0]))
    if (fields.get("name"), fields.get("versionCode"), fields.get("versionName")) != (
        "local.rea.shower", str(info["code"]), info["version"]
    ):
        raise ValueError("APK package or version differs from release metadata")
    if "application-debuggable" in output:
        raise ValueError("Refusing a debuggable APK")
    manifest = subprocess.check_output([aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
    if "UiPreviewActivity" in manifest or "WidgetSmokeInstrumentation" in manifest:
        raise ValueError("Test components must not be present in release")
    with zipfile.ZipFile(apk) as archive:
        required = {"assets/legal/LICENSE", "assets/legal/THIRD_PARTY_NOTICES.md", "assets/legal/licenses/Apache-2.0.txt"}
        if not required.issubset(archive.namelist()):
            raise ValueError("APK is missing bundled license notices")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("--github-output", action="store_true")
    args = parser.parse_args()
    info = version()
    if args.tag is not None:
        check_tag(info, args.tag)
    if args.apk:
        check_apk(info, args.apk, args.aapt)
    if args.github_output:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            for key, value in info.items():
                output.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    print(json.dumps(info, ensure_ascii=False))


if __name__ == "__main__":
    main()
