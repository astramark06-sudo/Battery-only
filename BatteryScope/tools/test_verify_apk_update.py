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
