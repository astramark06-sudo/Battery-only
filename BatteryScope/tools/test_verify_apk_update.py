import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import verify_apk_update as gate

CERT = "a" * 64
OTHER = "b" * 64
CHECKSUM = "c" * 64
IDENTITY = gate.ApkIdentity(gate.PACKAGE, 17, CERT, CHECKSUM)
BADGING = "package: name='com.batteryscope' versionCode='17' versionName='1.16'\n"
SIGNER = f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {CERT}\n"
# Intentionally independent of the implementation allowlist to catch policy drift.
PERMISSIONS = (
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "moe.shizuku.manager.permission.API_V23",
)
PERMISSION_BADGING = "".join(f"uses-permission: name='{p}'\n" for p in PERMISSIONS)
RELEASE = BADGING + PERMISSION_BADGING + "application: label='BatteryScope' icon='icon.png'\n"


class UpdateGateTest(unittest.TestCase):
    def test_signer_digest_is_not_apk_or_public_key_digest(self):
        self.assertEqual(CERT, gate.parse_signer(SIGNER + f"Signer #1 public key SHA-256 digest: {OTHER}\n"))
        for wrong in ("", f"APK SHA-256: {CERT}", f"Signer #1 public key SHA-256 digest: {CERT}"):
            with self.assertRaises(gate.VerificationError):
                gate.parse_signer(wrong)

    def test_multisigner_fails_closed(self):
        with self.assertRaises(gate.VerificationError):
            gate.parse_signer(SIGNER + f"Signer #2 certificate SHA-256 digest: {OTHER}\n")

    def test_badging_rejects_other_app(self):
        self.assertEqual((gate.PACKAGE, 17), gate.parse_badging(BADGING))
        with self.assertRaises(gate.VerificationError):
            gate.parse_badging(BADGING.replace(gate.PACKAGE, "other.app"))

    def test_policy_rejects_mismatch_and_downgrade(self):
        gate.compare(IDENTITY, CERT.upper(), 16)
        for cert, version in [(OTHER,16),(CERT,18),(CERT,17),("not a fingerprint",16)]:
            with self.assertRaises(gate.VerificationError):
                gate.compare(IDENTITY, cert, version)
        gate.compare(IDENTITY, CERT, 17, allow_same=True)

    def test_inspect_uses_signed_apk_and_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "candidate.apk"
            path.write_bytes(b"synthetic fixture, not an actual apk")
            with patch.object(gate,"run_tool",side_effect=[SIGNER,BADGING]) as run:
                result = gate.inspect_apk(path,"signer","aapt")
                self.assertEqual(CERT,result.signer_sha256)
                self.assertEqual(gate.file_digest(path),result.apk_sha256)
                self.assertEqual("verify",run.call_args_list[0].args[0][1])

    def test_release_metadata_rejects_debug_wrong_version_and_internet(self):
        release = RELEASE
        gate.check_release_badging(release, "1.16")
        for badging, version in [
            (release + "application-debuggable\n", "1.16"),
            (release, "1.17"),
            (release, ""),
            (BADGING, "1.16"),
            (release.replace(" versionName='1.16'", ""), "1.16"),
            (release + "uses-permission: name='android.permission.INTERNET'\n", "1.16"),
            (release + "uses-permission-sdk-23: name='android.permission.INTERNET'\n", "1.16"),
        ]:
            with self.subTest(badging=badging, version=version):
                with self.assertRaises(gate.VerificationError):
                    gate.check_release_badging(badging, version)
    def test_permission_policy_rejects_extra_missing_duplicate_and_conditional(self):
        gate.check_permission_badging(PERMISSION_BADGING)
        gate.check_permission_badging("".join(reversed(PERMISSION_BADGING.splitlines(keepends=True))))
        for permission in PERMISSIONS:
            line = f"uses-permission: name='{permission}'\n"
            for invalid in (
                PERMISSION_BADGING.replace(line, ""),
                PERMISSION_BADGING + line,
                PERMISSION_BADGING.replace(line, line.replace("uses-permission:", "uses-permission-sdk-23:")),
                PERMISSION_BADGING.replace(line, line.rstrip() + " maxSdkVersion='35'\n"),
            ):
                with self.subTest(invalid=invalid), self.assertRaises(gate.VerificationError):
                    gate.check_permission_badging(invalid)
        for unexpected in ("INTERNET", "ACCESS_NETWORK_STATE", "PACKAGE_USAGE_STATS", "READ_CONTACTS"):
            with self.subTest(unexpected=unexpected), self.assertRaises(gate.VerificationError):
                gate.check_release_badging(RELEASE + f"uses-permission: name='android.permission.{unexpected}'\n", "1.16")
        for suffix in (
            "uses-permission: malformed\n",
            "uses-implied-permission: name='android.permission.INTERNET' reason='test'\n",
            "  uses-permission-sdk-23: name='other.permission.TEST'\n",
            "uses-permission-unknown: name='other.permission.TEST'\n",
        ):
            with self.subTest(suffix=suffix), self.assertRaises(gate.VerificationError):
                gate.check_permission_badging(PERMISSION_BADGING + suffix)
        with self.assertRaises(gate.VerificationError):
            gate.check_permission_badging("")

    def test_release_cli_reports_exact_permission_policy(self):
        out = io.StringIO()
        with patch.object(gate,"locate",side_effect=["signer","aapt"]), \
                patch.object(gate,"inspect_apk",return_value=IDENTITY), contextlib.redirect_stdout(out):
            result = gate.main(["candidate.apk","--expected-cert",CERT,"--installed-version-code","16",
                                "--release-version-name","1.16"])
        self.assertEqual(0,result)
        report = json.loads(out.getvalue())
        self.assertEqual("IDENTITY_MATCH_ONLY", report["status"])
        self.assertEqual(sorted(PERMISSIONS), report["expected_permissions"])
        self.assertTrue(report["release_metadata_checked"])

    def test_release_mode_rejects_same_version_override(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), patch.object(gate,"locate") as locate:
            result = gate.main(["candidate.apk","--expected-cert",CERT,"--installed-version-code","16",
                                "--release-version-name","1.16","--allow-same-version"])
        self.assertEqual(1,result)
        self.assertIn("same-version",json.loads(out.getvalue())["reason"])
        locate.assert_not_called()

    def test_release_inspection_checks_candidate_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "candidate.apk"
            path.write_bytes(b"synthetic fixture")
            release = RELEASE
            with patch.object(gate,"run_tool",side_effect=[SIGNER,release]):
                self.assertEqual(CERT,gate.inspect_apk(path,"signer","aapt","1.16").signer_sha256)
            with patch.object(gate,"run_tool",side_effect=[SIGNER,release + "application-debuggable\n"]):
                with self.assertRaises(gate.VerificationError):
                    gate.inspect_apk(path,"signer","aapt","1.16")

    def test_cli_fails_closed_when_baseline_not_known(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            result = gate.main(["candidate.apk","--expected-cert",CERT])
        self.assertEqual(1,result)
        self.assertEqual("REJECTED",json.loads(out.getvalue())["status"])

    def test_cli_reports_identity_only(self):
        out = io.StringIO()
        with patch.object(gate,"locate",side_effect=["signer","aapt"]), \
                patch.object(gate,"inspect_apk",return_value=IDENTITY), contextlib.redirect_stdout(out):
            result = gate.main(["candidate.apk","--expected-cert",CERT,"--installed-version-code","16"])
        self.assertEqual(0,result)
        self.assertEqual("IDENTITY_MATCH_ONLY",json.loads(out.getvalue())["status"])

    def test_cli_rejects_mismatch(self):
        out = io.StringIO()
        with patch.object(gate,"locate",side_effect=["signer","aapt"]), \
                patch.object(gate,"inspect_apk",return_value=IDENTITY), contextlib.redirect_stdout(out):
            result = gate.main(["candidate.apk","--expected-cert",OTHER,"--installed-version-code","16"])
        self.assertEqual(1,result)
        self.assertIn("Signer mismatch",json.loads(out.getvalue())["reason"])


if __name__ == "__main__":
    unittest.main()
