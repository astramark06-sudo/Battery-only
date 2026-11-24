#!/usr/bin/env python3
"""Offline conservative APK update gate. Never installs, signs or reads a private key."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, asdict

PACKAGE = "com.batteryscope"
BUILD_TOOLS = "35.0.0"


class VerificationError(Exception):
    pass


@dataclass(frozen=True)
class ApkIdentity:
    package: str
    version_code: int
    signer_sha256: str
    apk_sha256: str


def normalize_digest(raw: str) -> str:
    digest = raw.strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise VerificationError("Expected a complete SHA-256 certificate fingerprint (64 hex characters)")
    return digest


def parse_badging(output: str) -> tuple[str, int]:
    matches = re.findall(r"^package: name='([^']+)' versionCode='([0-9]+)'", output, re.M)
    if len(matches) != 1:
        raise VerificationError("Could not establish a unique package and version code")
    package, version = matches[0]
    if package != PACKAGE or not 0 < int(version) <= 2_100_000_000:
        raise VerificationError("Unexpected package or version code")
    return package, int(version)


def parse_signer(output: str) -> str:
    # Do not confuse the APK checksum, key digest or source-stamp digest with the signer.
    matches = re.findall(r"^Signer #([0-9]+) certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$", output, re.M)
    if len(matches) != 1 or matches[0][0] != "1":
        raise VerificationError("Single verified signer required; multi-signer/rotation cases need manual review")
    count = re.findall(r"^Number of signers:\s*([0-9]+)\s*$", output, re.M)
    if count and count != ["1"]:
        raise VerificationError("Unexpected signer count")
    return normalize_digest(matches[0][1])


def locate(name: str, explicit: str | None) -> str:
    if explicit:
        path = Path(explicit).expanduser()
        if path.is_file():
            return str(path.resolve())
        found = shutil.which(explicit)
        if found:
            return found
        raise VerificationError(f"Tool not found: {name}")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    suffix = (".bat" if name == "apksigner" else ".exe") if os.name == "nt" else ""
    if sdk:
        path = Path(sdk) / "build-tools" / BUILD_TOOLS / (name + suffix)
        if path.is_file():
            return str(path.resolve())
    found = shutil.which(name)
    if found:
        return found
    raise VerificationError(f"Android SDK Build Tools {BUILD_TOOLS} required: {name}")


def run_tool(command: list[str]) -> str:
    try:
        completed = subprocess.run(command, check=False, capture_output=True,
                                   text=True, encoding="utf-8", errors="replace", timeout=120)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise VerificationError(f"Tool failed: {Path(command[0]).name}") from exc
    if completed.returncode != 0:
        raise VerificationError(f"{Path(command[0]).name} rejected the APK (exit {completed.returncode})")
    return completed.stdout


def file_digest(path: Path) -> str:
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else _stream_hash(file)


def _stream_hash(file) -> str:
    md = hashlib.sha256()
    for block in iter(lambda: file.read(1024 * 1024), b""):
        md.update(block)
    return md.hexdigest()


def inspect_apk(path: Path, signer: str, aapt: str) -> ApkIdentity:
    path = path.resolve(strict=True)
    if not path.is_file() or path.stat().st_size == 0:
        raise VerificationError("APK is missing or empty")
    before = file_digest(path)
    output = run_tool([signer, "verify", "--verbose", "--print-certs", str(path)])
    fingerprint = parse_signer(output)
    package, code = parse_badging(run_tool([aapt, "dump", "badging", str(path)]))
    if before != file_digest(path):
        raise VerificationError("APK changed during verification")
    return ApkIdentity(package, code, fingerprint, before)


def compare(candidate: ApkIdentity, expected_cert: str, installed_code: int, allow_same: bool = False) -> None:
    if candidate.package != PACKAGE or installed_code <= 0:
        raise VerificationError("Installed identity or candidate package is invalid")
    if candidate.signer_sha256 != normalize_digest(expected_cert):
        raise VerificationError("Signer mismatch: not a verified in-place update. Do not uninstall to bypass this check.")
    if candidate.version_code < installed_code or (candidate.version_code == installed_code and not allow_same):
        raise VerificationError("Candidate version must exceed the installed version (same version only for explicit developer testing)")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path)
    trust = parser.add_mutually_exclusive_group(required=True)
    trust.add_argument("--baseline", type=Path, help="Trusted exact APK of the installed app")
    trust.add_argument("--expected-cert", help="Trusted installed certificate SHA-256, not the APK checksum")
    parser.add_argument("--installed-version-code", type=int)
    parser.add_argument("--allow-same-version", action="store_true", help="Developer verification only, not release approval")
    parser.add_argument("--apksigner")
    parser.add_argument("--aapt")
    args = parser.parse_args(argv)
    report = {"status": "REJECTED", "scope": "package, version, exact signing certificate; no install or device test"}
    try:
        if args.baseline and args.installed_version_code is not None:
            raise VerificationError("Use either baseline APK or installed certificate plus version code")
        if args.expected_cert and (args.installed_version_code is None or args.installed_version_code <= 0):
            raise VerificationError("Trusted installed version code required with certificate")
        signer, aapt = locate("apksigner", args.apksigner), locate("aapt", args.aapt)
        candidate = inspect_apk(args.candidate, signer, aapt)
        report["candidate"] = asdict(candidate)
        if args.baseline:
            baseline = inspect_apk(args.baseline, signer, aapt)
            report["baseline"] = asdict(baseline)
            cert, code = baseline.signer_sha256, baseline.version_code
        else:
            cert, code = normalize_digest(args.expected_cert), args.installed_version_code
        compare(candidate, cert, code, args.allow_same_version)
        report["status"] = "IDENTITY_MATCH_ONLY"
        report["expected_cert"] = cert
        report["installed_version_code"] = code
        report["notice"] = "No actual installation, Android migration, device validation or release approval performed."
        print(json.dumps(report, indent=2))
        return 0
    except (VerificationError, OSError) as exc:
        report["reason"] = str(exc)
        print(json.dumps(report, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
