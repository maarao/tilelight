# usage: timeline.py <trace> <pid> [start_ms] [length_ms]
# Prints the game's GPU work per command buffer and CPU commits in time order, to see where a frame idles.
import subprocess, sys, xml.etree.ElementTree as ET
from collections import defaultdict

trace, pid = sys.argv[1], sys.argv[2]
t0 = float(sys.argv[3]) if len(sys.argv) > 3 else 2000.0
span = float(sys.argv[4]) if len(sys.argv) > 4 else 16.0


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
            cells[name] = ids[el.attrib["ref"]] if "ref" in el.attrib else el
        yield cells


def ours(c):
    p = c.get("process")
    return p is not None and f"({pid})" in p.attrib.get("fmt", "")


lo, hi = t0 * 1e6, (t0 + span) * 1e6
events = []
cb = defaultdict(lambda: [1e30, 0, set()])
for c in table("metal-gpu-intervals"):
    if not ours(c) or c["start"].tag == "sentinel":
        continue
    s = int(c["start"].text)
    e = s + int(c["duration"].text)
    if e < lo or s > hi:
        continue
    key = c["cmdbuffer-id"].attrib.get("fmt", "?") if c.get("cmdbuffer-id") is not None else "?"
    entry = cb[key]
    entry[0] = min(entry[0], s)
    entry[1] = max(entry[1], e)
    entry[2].add(c["channel-name"].text)
labels = {}
for c in table("metal-object-label"):
    labels[hex(int(c["object-id"].text))] = c["label"].text or ""
for c in table("metal-application-command-buffer-submissions"):
    if not ours(c):
        continue
    t = int(c["start"].text) + int(c["duration"].text)
    key = c["cmdbuffer-id"].attrib.get("fmt", "?") if c.get("cmdbuffer-id") is not None else "?"
    label = labels.get(key, "")
    if lo <= t <= hi:
        thread = c["thread"].attrib.get("fmt", "?").split(" (java")[0]
        events.append((t, f"   commit {key} {label!r} from {thread}"))
for key, (s, e, ch) in cb.items():
    events.append((s, f"GPU start {key} {labels.get(key, '')!r} {sorted(ch)}"))
    events.append((e, f"GPU end   {key} ({(e - s) / 1e3:.0f} us)"))
events.sort()
prev = None
for t, text in events:
    gap = "" if prev is None else f"+{(t - prev) / 1e3:7.0f}"
    print(f"{(t - lo) / 1e6:8.3f} ms {gap:>9} us  {text}")
    prev = t
