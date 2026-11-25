"""Query OSV for selected public Maven dependency coordinates. No application data."""
import argparse
import datetime
import json
import re
from pathlib import Path
from urllib.request import Request, urlopen

URL = "https://api.osv.dev/v1/querybatch"
SCOPES = {
    "debugRuntimeClasspath", "debugUnitTestRuntimeClasspath",
    "debugAndroidTestRuntimeClasspath", "rootBuildscriptClasspath",
    "appBuildscriptClasspath",
}
COORD = re.compile(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+~-]+")


def parse_inventory(text):
    scope = None
    seen = set()
    by_coordinate = {}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            scope = line[1:-1]
            if scope not in SCOPES or scope in seen:
                raise ValueError("Unexpected or duplicate dependency scope")
            seen.add(scope)
        elif scope is None and line.startswith(("Gradle ", "Java ")):
            continue
        elif scope is None or not COORD.fullmatch(line):
            raise ValueError("Malformed Maven coordinate or missing scope")
        else:
            by_coordinate.setdefault(line, set()).add(scope)
    if seen != SCOPES:
        raise ValueError("Missing dependency scope")
    for expected in ("debugRuntimeClasspath", "debugUnitTestRuntimeClasspath",
                     "debugAndroidTestRuntimeClasspath"):
        if not any(expected in scopes for scopes in by_coordinate.values()):
            raise ValueError("Empty required dependency scope: " + expected)
    build_scopes = {"rootBuildscriptClasspath", "appBuildscriptClasspath"}
    for prefix in ("com.android.tools.build:gradle:",
                   "org.jetbrains.kotlin:kotlin-gradle-plugin:"):
        if not any(c.startswith(prefix) and scopes & build_scopes
                   for c, scopes in by_coordinate.items()):
            raise ValueError("Missing resolved build plugin: " + prefix)
    return {c: sorted(scopes) for c, scopes in sorted(by_coordinate.items())}


def post_batch(queries):
    request = Request(URL, data=json.dumps({"queries": queries}).encode(),
                      headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(request, timeout=45) as response:
        data = response.read(8_388_609)
    if len(data) > 8_388_608:
        raise ValueError("OSV response exceeds safety limit")
    return json.loads(data)


def scan(coordinates, post=post_batch):
    """Preserve query/result association and follow every pagination token."""
    findings = {c: set() for c in coordinates}
    names = list(coordinates)
    for offset in range(0, len(names), 100):
        pending = []
        tokens = set()
        for c in names[offset:offset + 100]:
            group, artifact, version = c.split(":")
            pending.append((c, {"package": {"ecosystem": "Maven",
                                            "name": group + ":" + artifact},
                                "version": version}))
        for _ in range(20):
            if not pending:
                break
            reply = post([q for _, q in pending])
            if not isinstance(reply, dict) or not isinstance(reply.get("results"), list):
                raise ValueError("Malformed OSV response")
            results = reply["results"]
            if len(results) != len(pending):
                raise ValueError("OSV result count mismatch")
            following = []
            for (c, query), result in zip(pending, results):
                if not isinstance(result, dict) or set(result) - {"vulns", "next_page_token"}:
                    raise ValueError("Malformed or unexpected OSV result")
                vulns = result.get("vulns", [])
                if not isinstance(vulns, list):
                    raise ValueError("Malformed advisory list")
                for vuln in vulns:
                    if not isinstance(vuln, dict) or not isinstance(vuln.get("id"), str):
                        raise ValueError("Malformed advisory ID")
                    findings[c].add(vuln["id"])
                token = result.get("next_page_token")
                if token:
                    if not isinstance(token, str) or (c, token) in tokens:
                        raise ValueError("Invalid or repeated OSV pagination token")
                    tokens.add((c, token))
                    following.append((c, dict(query, page_token=token)))
            pending = following
        if pending:
            raise ValueError("OSV pagination limit reached; scan incomplete")
    return {c: sorted(ids) for c, ids in findings.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    report = {
        "scanned_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "service": URL,
        "scope": "Resolved public Maven runtime, test and buildscript components",
        "limitations": "Advisory match only; no exploitability proof, native/SDK scan, "
                       "unmapped product advisory check, or dependency integrity verification.",
        "status": "incomplete",
    }
    exit_code = 1
    try:
        coordinates = parse_inventory(args.inventory.read_text(encoding="utf-8"))
        matches = scan(coordinates)
        report["components"] = [
            {"coordinate": c, "scopes": scopes, "advisory_ids": matches[c]}
            for c, scopes in coordinates.items()
        ]
        report["component_count"] = len(coordinates)
        report["matched_component_count"] = sum(bool(ids) for ids in matches.values())
        report["status"] = "findings" if report["matched_component_count"] else "no_known_matches"
        # Findings are evidence for triage, not a claim of app exploitability.
        exit_code = 0
    except Exception as exc:
        report["error"] = type(exc).__name__ + ": " + str(exc)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("OSV dependency audit: " + report["status"])
    if report["status"] == "findings":
        print("Review matched component scopes and advisory IDs before release.")
    if "error" in report:
        print(report["error"])
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
