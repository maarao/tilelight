# usage: setcam.py <world_dir>  -- put the singleplayer player at a fixed camera, flying
import nbtlib, sys, glob, os
from nbtlib.tag import Double, Float, Byte, List
w = sys.argv[1]
def fix(p):
    p["Pos"] = List[Double]([Double(-24.5), Double(100.0), Double(6.5)])
    p["Rotation"] = List[Float]([Float(0.0), Float(20.0)])
    p["Motion"] = List[Double]([Double(0), Double(0), Double(0)])
    p["abilities"]["flying"] = Byte(1)
    p["OnGround"] = Byte(0)
n = 0
lvl = nbtlib.load(os.path.join(w, "level.dat"))
if "Player" in lvl["Data"]:
    fix(lvl["Data"]["Player"]); lvl.save(); n += 1
for f in glob.glob(os.path.join(w, "playerdata", "*.dat")) + glob.glob(os.path.join(w, "players", "data", "*.dat")):
    t = nbtlib.load(f); fix(t); t.save(); n += 1
print(f"setcam: fixed {n} player record(s)")
