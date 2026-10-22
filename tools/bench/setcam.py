# usage: setcam.py <world_dir>  -- put the singleplayer player at a fixed camera, flying
# CAM="<dimension> x y z yaw pitch" picks another camera (default: the Overworld reference scene).
import nbtlib, sys, glob, os
from nbtlib.tag import Double, Float, Byte, List, String
w = sys.argv[1]
cam = os.environ.get("CAM", "minecraft:overworld -24.5 100.0 6.5 0.0 20.0").split()
dim, (x, y, z, yaw, pitch) = cam[0], map(float, cam[1:])
def fix(p):
    p["Dimension"] = String(dim)
    p["Pos"] = List[Double]([Double(x), Double(y), Double(z)])
    p["Rotation"] = List[Float]([Float(yaw), Float(pitch)])
    p["Motion"] = List[Double]([Double(0), Double(0), Double(0)])
    p["abilities"]["flying"] = Byte(1)
    p["OnGround"] = Byte(0)
    # /posteffect state is saved with the player; a leftover creeper filter once skewed a whole sweep.
    if "post_effects" in p:
        del p["post_effects"]
n = 0
lvl = nbtlib.load(os.path.join(w, "level.dat"))
if "Player" in lvl["Data"]:
    fix(lvl["Data"]["Player"]); lvl.save(); n += 1
for f in glob.glob(os.path.join(w, "playerdata", "*.dat")) + glob.glob(os.path.join(w, "players", "data", "*.dat")):
    t = nbtlib.load(f); fix(t); t.save(); n += 1
print(f"setcam: fixed {n} player record(s)")
