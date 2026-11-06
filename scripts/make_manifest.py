#!/usr/bin/env python3
"""Produce OTA update.json from an already signed APK."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote


def cumulative_history(version, code, entries):
    """Require a gap-free, complete, user-facing changelog through the release being built."""
    if not isinstance(entries, list) or not entries or len(entries) > 500:
        raise ValueError("Missing or oversized release history")
    selected = []
    prev = 18  # Baseline v1.5.0 (18): history starts with v1.5.1 (19).
    for item in entries:
        if not isinstance(item, dict):
            raise ValueError("Malformed history entry")
        n = item.get("versionCode")
        name = item.get("versionName")
        notes = item.get("notes")
        if type(n) is not int or n != prev + 1 or name != "1.5." + str(n - 18):
            raise ValueError("Missing, duplicated or mismatched release history")
        if not isinstance(notes, str) or not (5 <= len(notes) <= 100_000):
            raise ValueError("Missing or oversized user-facing notes")
        lines = notes.splitlines()
        if any(len(line) > 260 or not line.startswith(("[+] ", "[-] ", "[–] ", "[=] "))
               or not line[4:].strip() for line in lines):
            raise ValueError("Invalid release note markup")
        if n <= code:
            selected.append(item)
        prev = n
    if not selected or selected[-1]["versionCode"] != code or selected[-1]["versionName"] != version:
        raise ValueError("Missing notes for selected APK version")
    if sum(len(item["notes"]) for item in selected) > 350_000:
        raise ValueError("Release history too large")
    return selected


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--github-output", required=True)
    args = parser.parse_args()

    if args.repo.casefold() not in ("antiferment/ax8pro", "infend/ax8pro"):
        raise SystemExit("Unexpected repository: " + args.repo)
    apk = Path(args.apk)
    if not apk.is_file():
        raise SystemExit("APK missing: " + str(apk))
    details = subprocess.check_output([args.aapt, "dump", "badging", str(apk)], text=True)
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", details, re.MULTILINE)
    if not match:
        raise SystemExit("Unable to parse APK version using aapt")
    package_name, code, version = match.groups()
    if not re.fullmatch(r"[A-Za-z0-9._-]+", version):
        raise SystemExit("Invalid versionName for a Git tag")
    if package_name != "ru.xarkon.ax8pro" or int(code) < 1:
        raise SystemExit("Unexpected APK package or versionCode: " + package_name + " " + code)
    tag = "v" + version
    # Stream the APK rather than loading the entire release into memory.
    with apk.open("rb") as apk_file:
        sha = hashlib.file_digest(apk_file, "sha256").hexdigest()
    history_file = Path(__file__).resolve().parents[1] / "release_notes" / "history.json"
    history = cumulative_history(version, int(code),
        json.loads(history_file.read_text(encoding="utf-8")))
    data = {
        "schema": 1,
        "packageName": package_name,
        "versionCode": int(code),
        "versionName": version,
        # Keep the pre-rename URL for OTA clients that pin the legacy path.
        # GitHub redirects it to the canonical INFEND/AX8PRO repository.
        "apkUrl": f"https://github.com/ANTIFERMENT/AX8PRO/releases/download/{quote(tag)}/AX8PRO.apk",
        "sha256": sha,
        "sizeBytes": apk.stat().st_size,
        "publishedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "notes": history[-1]["notes"],
        "history": history
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with open(args.github_output, "a", encoding="utf-8") as f:
        f.write("tag=" + tag + "\n")
        f.write("version=" + version + "\n")
        f.write("version_code=" + code + "\n")
    print("OTA metadata created:", package_name, version, code)

if __name__ == "__main__":
    main()
