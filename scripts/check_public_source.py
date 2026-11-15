#!/usr/bin/env python3
"""Check the explicit public file list and common private-data signatures."""
import argparse
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BINARY_FILES = {
    "gradle/wrapper/gradle-wrapper.jar",
    "app/src/main/res/drawable-nodpi/widget_preview.png",
    "docs/images/app-device.png",
    "docs/images/app-login.png",
    "docs/images/app-profile.png",
    "docs/images/app-widget-setup.png",
    "docs/images/widget-close.png",
    "docs/images/widget-open.png",
}
PHONE_EXAMPLES = {"13800000000", "13800001234", "13800005678", "13900000000"}
SIGNATURES = [
    ("private key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b")),
    ("AWS access key", re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("bearer credential", re.compile(r"(?i)Bearer\s+[A-Za-z0-9_.+/=-]{24,}")),
    ("local home path", re.compile(r"/(?:home|Users)/[A-Za-z0-9_.-]+/")),
    ("outside-workspace reference", re.compile(r"(?:\.\./){2}(?:tools|docs|wmxy|captures|scripts|design-system)/")),
]


def public_files(root):
    entries = [line.strip() for line in (root / "PUBLIC_FILES.txt").read_text().splitlines()
               if line.strip() and not line.startswith("#")]
    if len(entries) != len(set(entries)):
        raise ValueError("Duplicate public file entry")
    for name in entries:
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or ".git" in path.parts or "\\" in name:
            raise ValueError("Unsafe public file entry")
        if (set(path.parts) & {"captures", "wmxy", "tools", "artifacts", ".gradle", "build"}
                or path.suffix.lower() in {".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".log"}
                or path.name in {"local.properties", "account.enc", ".env", "signing.properties", "keystore.properties"}):
            raise ValueError("Private or generated files must never be in the public manifest")
        if any((root / Path(*path.parts[:i])).is_symlink() for i in range(1, len(path.parts) + 1)):
            raise ValueError("Symlinks are not accepted in the public export")
    return set(entries)


def check(root, actual):
    expected = public_files(root)
    errors = []
    for name in sorted(actual - expected):
        errors.append(f"Unapproved file: {name}")
    for name in sorted(expected - actual):
        errors.append(f"Missing public file: {name}")
    for name in sorted(expected & actual):
        path = root / name
        if not path.is_file():
            errors.append(f"Not a regular file: {name}")
            continue
        if name in BINARY_FILES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeError:
            errors.append(f"Unexpected binary: {name}")
            continue
        for description, pattern in SIGNATURES:
            if pattern.search(text):
                errors.append(f"Review {description}: {name}")
        phones = set(re.findall(r"(?<!\d)1[3-9]\d{9}(?!\d)", text)) - PHONE_EXAMPLES
        if phones:
            errors.append(f"Review non-example phone number: {name}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--git", action="store_true", help="Inspect tracked files; required in CI")
    args = parser.parse_args()
    if args.git:
        top = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], cwd=ROOT, text=True).strip()
        if Path(top).resolve() != ROOT:
            sys.exit("The APP must be the repository root")
        raw = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
        actual = set(raw.decode().rstrip("\0").split("\0"))
    else:
        # Used on a new, clean export before any build outputs or local configuration exist.
        actual = {p.relative_to(ROOT).as_posix() for p in ROOT.rglob("*")
                  if p.is_file() and ".git" not in p.relative_to(ROOT).parts}
    errors = check(ROOT, actual)
    if errors:
        sys.exit("\n".join(errors))
    print(f"Public source check passed: {len(actual)} explicitly approved files")


if __name__ == "__main__":
    main()
