# usage: gaps.py <trace> <pid>
# Classifies the game's GPU idle time from a Metal System Trace.
#
# For each idle gap on the GPU, finds the last command buffer the game committed before the gap ended:
#   committed during the gap -> "starved": the GPU had nothing to do until the CPU sent work
#   committed before the gap -> "queued": work was waiting, but the GPU sat idle anyway
#                               (cross-queue event waits, scheduling)
import subprocess, sys, xml.etree.ElementTree as ET
from collections import Counter

trace, pid = sys.argv[1], sys.argv[2]


def table(schema):
    xml = subprocess.run(
        ["xcrun", "xctrace", "export", "--input", trace, "--xpath",
         f'/trace-toc/run[@number="1"]/data/table[@schema="{schema}"]'],
        capture_output=True, check=True).stdout
    root = ET.fromstring(xml)
    ids = {}
    cols = [c.findtext("mnemonic") for c in root.iter("col")]
    for row in root.iter("row"):
        for e in row.iter():
            if "id" in e.attrib:
                ids[e.attrib["id"]] = e
        cells = {}
        for name, el in zip(cols, list(row)):
            if "ref" in el.attrib:
                el = ids[el.attrib["ref"]]
            cells[name] = el
        yield cells


def proc_pid(cells):
    proc = cells.get("process")
    return proc.attrib.get("fmt", "") if proc is not None else ""


busy = []
for c in table("metal-gpu-intervals"):
    if f"({pid})" not in proc_pid(c) or c["start"].tag == "sentinel":
        continue
    s = int(c["start"].text)
    busy.append((s, s + int(c["duration"].text)))

commits = []  # (time the CPU finished committing, thread)
for c in table("metal-application-command-buffer-submissions"):
    if f"({pid})" not in proc_pid(c):
        continue
    t = int(c["start"].text) + int(c["duration"].text)
    commits.append((t, c["thread"].attrib.get("fmt", "?").split(" (java")[0]))
commits.sort()

busy.sort()
merged = [list(busy[0])]
for s, e in busy[1:]:
    if s > merged[-1][1]:
        merged.append([s, e])
    else:
        merged[-1][1] = max(merged[-1][1], e)

window = merged[-1][1] - merged[0][0]
kinds = Counter()
threads = Counter()
import bisect
times = [t for t, _ in commits]
for (_, gap_start), (gap_end, _) in zip(merged, merged[1:]):
    gap = gap_end - gap_start
    if gap < 20_000:  # under 20 us: ordinary encoder-to-encoder bubbles
        kinds["tiny"] += gap
        continue
    i = bisect.bisect_right(times, gap_end) - 1
    if i >= 0 and times[i] > gap_start:
        kinds["starved"] += gap
        threads[commits[i][1]] += gap
    else:
        kinds["queued"] += gap

idle = window - sum(e - s for s, e in merged)
print(f"window {window / 1e6:.0f} ms, GPU idle {idle / 1e6:.0f} ms ({100 * idle / window:.1f}%)")
for k in ("starved", "queued", "tiny"):
    print(f"  {k:8s} {kinds[k] / 1e6:7.1f} ms ({100 * kinds[k] / window:4.1f}% of window)")
print("starved gaps ended by commits from:")
for t, ns in threads.most_common(5):
    print(f"  {t:40s} {ns / 1e6:7.1f} ms")
