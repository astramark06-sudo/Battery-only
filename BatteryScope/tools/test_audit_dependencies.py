import unittest

from audit_dependencies import parse_inventory, scan, summarize_scopes


INVENTORY = """Gradle 8.9
Java 17
[debugRuntimeClasspath]
org.jetbrains.kotlin:kotlin-stdlib:2.0.21
[debugUnitTestRuntimeClasspath]
org.jetbrains.kotlin:kotlin-stdlib:2.0.21
junit:junit:4.13.2
[debugAndroidTestRuntimeClasspath]
androidx.test:runner:1.6.2
[rootBuildscriptClasspath]
com.android.tools.build:gradle:8.7.3
org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21
[appBuildscriptClasspath]
"""


class DependencyAuditTest(unittest.TestCase):
    def test_scope_deduplication_and_build_plugins(self):
        parsed = parse_inventory(INVENTORY)
        self.assertEqual(len(parsed), 5)
        self.assertEqual(parsed["org.jetbrains.kotlin:kotlin-stdlib:2.0.21"],
                         ["debugRuntimeClasspath", "debugUnitTestRuntimeClasspath"])

    def test_rejects_incomplete_or_invalid_inventory(self):
        for invalid in (
            INVENTORY.replace("[appBuildscriptClasspath]", ""),
            INVENTORY.replace("com.android.tools.build:gradle:8.7.3", ""),
            INVENTORY.replace("junit:junit:4.13.2", "private field"),
            INVENTORY.replace("[debugRuntimeClasspath]", "[debugRuntimeClasspath]\n[debugRuntimeClasspath]"),
        ):
            with self.subTest(inventory=invalid), self.assertRaises(ValueError):
                parse_inventory(invalid)

    def test_batch_and_pagination_preserve_association(self):
        coordinates = {"g:a:1": [], "g:b:2": []}
        calls = []

        def post(queries):
            calls.append(queries)
            if len(calls) == 1:
                return {"results": [{"vulns": [{"id": "GHSA-1"}], "next_page_token": "p"}, {}]}
            self.assertEqual(queries, [{"package": {"ecosystem": "Maven", "name": "g:a"},
                                        "version": "1", "page_token": "p"}])
            return {"results": [{"vulns": [{"id": "GHSA-1"}, {"id": "GHSA-2"}]}]}

        self.assertEqual(scan(coordinates, post), {"g:a:1": ["GHSA-1", "GHSA-2"], "g:b:2": []})
        self.assertEqual(len(calls), 2)

    def test_rejects_malformed_or_truncated_response(self):
        for reply in ({}, {"results": []}, {"results": [{"error": "bad"}]},
                      {"results": [{"vulns": [{"modified": "missing ID"}]}]}):
            with self.subTest(reply=reply), self.assertRaises(ValueError):
                scan({"g:a:1": []}, lambda _: reply)

    def test_scope_summary_counts_shared_component_without_inventing_runtime_matches(self):
        coordinates = {
            "g:runtime:1": ["debugRuntimeClasspath", "debugUnitTestRuntimeClasspath"],
            "g:test:1": ["debugUnitTestRuntimeClasspath"],
            "g:build:1": ["rootBuildscriptClasspath"],
        }
        matches = {"g:runtime:1": [], "g:test:1": ["GHSA-1"], "g:build:1": ["GHSA-1","GHSA-2"]}
        result = summarize_scopes(coordinates,matches)
        self.assertEqual(result["debugRuntimeClasspath"], {
            "component_count": 1, "matched_component_count": 0, "distinct_advisory_count": 0})
        self.assertEqual(result["debugUnitTestRuntimeClasspath"]["matched_component_count"],1)
        self.assertEqual(result["rootBuildscriptClasspath"]["distinct_advisory_count"],2)
        matches["g:runtime:1"] = ["GHSA-1"]
        self.assertEqual(summarize_scopes(coordinates,matches)["debugRuntimeClasspath"]["matched_component_count"],1)
        with self.assertRaises(ValueError):
            summarize_scopes(coordinates,{})

    def test_repeated_token_is_incomplete(self):
        with self.assertRaises(ValueError):
            scan({"g:a:1": []}, lambda _: {"results": [{"next_page_token": "same"}]})


if __name__ == "__main__":
    unittest.main()
