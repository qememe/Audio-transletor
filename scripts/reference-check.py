#!/usr/bin/env python3
"""Optional interoperability check. Requires PySSTV==0.5.9 and Pillow.

Generates signals using a third-party encoder, feeds the built AudioALL CLI,
and checks both VIS recognition and image reconstruction. Not a runtime dependency.
"""
import pathlib
import subprocess
import sys
from PIL import Image
from pysstv.color import MartinM1, Robot36, PD90, PD120


class PD50(PD90):
    VIS_CODE = 93
    PIXEL = 0.286


root = pathlib.Path(__file__).resolve().parent.parent
work = root / "build" / "reference"
work.mkdir(parents=True, exist_ok=True)
java = sys.argv[1] if len(sys.argv) > 1 else "java"
for cls, mode in [(Robot36, "ROBOT36"), (MartinM1, "MARTIN1"), (PD50, "PD50"), (PD120, "PD120")]:
    w, h = cls.WIDTH, cls.HEIGHT
    original = Image.new("RGB", (w, h))
    original.putdata([(20 + 210*x//(w-1), 20 + 210*y//(h-1), 30 + 180*(x+y)//(w+h-2)) for y in range(h) for x in range(w)])
    original.save(work / (mode + "-source.png"))
    wav = work / (mode + ".wav")
    cls(original, 48000, 16).write_wav(str(wav))
    out = work / (mode + "-received")
    out.mkdir(exist_ok=True)
    for old in out.glob("*.png"):
        old.unlink()
    subprocess.run([java, "-jar", str(root / "dist" / "AudioALL.jar"), "decode", "--wav", str(wav), "--out", str(out)], check=True)
    files = list(out.glob("*.png"))
    assert len(files) == 1, (mode, "expected one image", files)
    result = Image.open(files[0]).convert("RGB")
    assert result.size == (w, h), (mode, result.size)
    # PySSTV headers may carry a different image lead-in; accept only small reconstruction error.
    errors = sum((a-b)**2 for src, dst in zip(original.getdata(), result.getdata()) for a, b in zip(src, dst))
    rmse = (errors/(w*h*3))**.5
    assert rmse < 20, (mode, "RMSE", rmse)
    print(f"PASS independent PySSTV → AudioALL: {mode}, RMSE {rmse:.2f}", flush=True)
