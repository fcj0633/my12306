"""Export allowlisted performance evidence; never export sessions, logs or JARs."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OUT = HERE / "evidence"
WARM = HERE / "results/warm-purchase-clean"
QUERY = ROOT / "docs/5-后续开发规划/04-并发性能测试/results/seat-allocation-810"
manifest = []

def export(source, relative):
    data = source.read_bytes()
    text = data.decode("utf-8-sig")
    if re.search(r"eyJ[A-Za-z0-9_-]{15,}|Bearer\s+[A-Za-z0-9]|[\"'](?:password|access_token|refresh_token|authorization)[\"']\s*:", text, re.I):
        raise ValueError(f"Sensitive material detected in {source.name}")
    dest = OUT / relative
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, dest)
    manifest.append(dict(file=relative, source=source.relative_to(ROOT).as_posix(),
                         bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))

for name in ["comparison-matrix.json", "warm-summary.json", "warm-final-verification.json"]:
    export(WARM / name, "warm/" + name)
rows = json.loads((WARM / "comparison-matrix.json").read_text(encoding="utf-8"))
assert len(rows) == 36 and sum(row["success"] for row in rows) == 4973
for row in rows:
    for suffix in ["-requests.csv", "-cleanup.json"]:
        export(WARM / (row["tag"] + suffix), "warm/rounds/" + row["tag"] + suffix)
for name in ["benchmark.json", "source-metadata.json", "final-verification.json", "schema.sql", "business-natural-plan.txt"]:
    export(QUERY / name, "query-810/" + name)
selected = ["CarriageDirectoryTest", "CarriageReservationTest", "PurchaseMetadataServiceTest",
            "PurchaseTicketOrchestrationTest", "PurchaseTicketServiceTest", "SeatAllocatorTest",
            "TicketAvailabilityTokenBucketTest", "TicketCallbackServiceTest"]
suites = []
# Extract test names and counts only; omit XML properties and captured application output.
for name in selected:
    matches = list((ROOT / "12306/my12306/services/ticket-services/target/surefire-reports").glob("TEST-*." + name + ".xml"))
    assert len(matches) == 1, name
    suite = ET.parse(matches[0]).getroot()
    suites.append(dict(name=name, tests=int(suite.get("tests")), failures=int(suite.get("failures")),
                       errors=int(suite.get("errors")), skipped=int(suite.get("skipped")),
                       cases=[x.get("name") for x in suite.findall("testcase")],
                       sourceSha256=hashlib.sha256(matches[0].read_bytes()).hexdigest()))
assert sum(x["tests"] for x in suites) == 49
assert not any(x["failures"] or x["errors"] for x in suites)
OUT.mkdir(parents=True, exist_ok=True)
(OUT / "test-summary.json").write_text(json.dumps(suites, ensure_ascii=False, indent=2), encoding="utf-8")
(OUT / "manifest.json").write_text(json.dumps(dict(exportedOn="2026-10-10", formalRounds=36,
    formalSuccess=4973, files=manifest, omitted="Session files, full service logs, JARs, raw traces and exploratory runs remain local and ignored."),
    ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Exported {len(manifest)} allowlisted files and 49-test summary to {OUT}")
