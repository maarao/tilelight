# usage: gpuparse.py <trace> [pid]
# Per-process GPU busy time (union of all channel intervals) from a Metal System Trace.
import subprocess, sys, xml.etree.ElementTree as ET
from collections import defaultdict

trace = sys.argv[1]
want_pid = sys.argv[2] if len(sys.argv) > 2 else None
xml = subprocess.run(
    ["xcrun", "xctrace", "export", "--input", trace, "--xpath",
     '/trace-toc/run[@number="1"]/data/table[@schema="metal-gpu-intervals"]'],
    capture_output=True, check=True).stdout
root = ET.fromstring(xml)

ids = {}
def val(el):
    """Resolve xctrace's id/ref de-duplication; return (text, fmt, element)."""
    if el is None:
        return None
    if "ref" in el.attrib:
        el = ids[el.attrib["ref"]]
    elif "id" in el.attrib:
        ids[el.attrib["id"]] = el
    return el

def union(iv):
    iv = sorted(iv); total = 0; cs, ce = iv[0]
    for s, e in iv[1:]:
        if s > ce: total += ce - cs; cs, ce = s, e
        else: ce = max(ce, e)
    return total + ce - cs

def index(el):
    # register every id in the subtree so later refs resolve
    for e in el.iter():
        if "id" in e.attrib:
            ids[e.attrib["id"]] = e

cols = [c.findtext("mnemonic") for c in root.iter("col")]
busy = defaultdict(list)
chan = defaultdict(lambda: defaultdict(list))
frames = defaultdict(set)
t_min, t_max = None, None
for row in root.iter("row"):
    index(row)
    cells = dict(zip(cols, list(row)))
    start = val(cells["start"]); dur = val(cells["duration"]); proc = val(cells["process"])
    frame = val(cells.get("frame-number"))
    if start is None or dur is None or proc is None or proc.tag == "sentinel":
        continue
    s = int(start.text); d = int(dur.text)
    pid_el = proc.find("pid")
    pid_el = val(pid_el) if pid_el is not None else None
    name = proc.attrib.get("fmt", "?")
    busy[name].append((s, s + d))
    ch = val(cells.get("channel-name")); chan[name][ch.text if ch is not None else "?"].append((s, s + d))
    if frame is not None and frame.tag != "sentinel" and frame.text:
        frames[name].add(int(frame.text))
    t_min = s if t_min is None else min(t_min, s)
    t_max = s + d if t_max is None else max(t_max, s + d)

window = (t_max - t_min) / 1e9
print(f"window {window:.2f} s")
for name, iv in sorted(busy.items(), key=lambda kv: -sum(e - s for s, e in kv[1])):
    if want_pid and want_pid not in name:
        continue
    iv.sort(); total = 0; cs, ce = iv[0]
    for s, e in iv[1:]:
        if s > ce:
            total += ce - cs; cs, ce = s, e
        else:
            ce = max(ce, e)
    total += ce - cs
    n = len(frames[name])
    per = f"{total / 1e6 / n:.2f} ms/frame over {n} frames ({n / window:.1f} fps)" if n else "no frame numbers"
    print(f"{name:40s} busy {total / 1e6:8.1f} ms ({100 * total / 1e9 / window:5.1f}% of window) | {per}")
    for c, civ in sorted(chan[name].items()):
        cb = union(civ)
        print(f"    {c:12s} busy {cb / 1e6:8.1f} ms" + (f" = {cb / 1e6 / n:.2f} ms/frame" if n else ""))
