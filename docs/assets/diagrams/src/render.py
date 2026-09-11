"""
Renders every SVG in ./out to a 2x PNG next to it with headless Chrome (or Edge).

Handles: reading each SVG's own width/height so the screenshot matches the canvas exactly.
"""
import os, re, subprocess, sys, glob

HERE = os.path.dirname(os.path.abspath(__file__))
BROWSERS = [r"C:\Program Files\Google\Chrome\Application\chrome.exe",
            r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"]
browser = next(b for b in BROWSERS if os.path.exists(b))

only = set(sys.argv[1:])
for svg in sorted(glob.glob(os.path.join(HERE, "out", "*.svg"))):
    name = os.path.splitext(os.path.basename(svg))[0]
    if only and name not in only:
        continue
    head = open(svg, encoding="utf-8").read(400)
    w, h = (int(float(v)) for v in re.search(r'width="([\d.]+)" height="([\d.]+)"', head).groups())
    png = os.path.splitext(svg)[0] + ".png"
    url = "file:///" + svg.replace("\\", "/")
    subprocess.run([browser, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=2",
                    "--default-background-color=00000000", f"--window-size={w},{h}", f"--screenshot={png}", url],
                   check=True, capture_output=True)
    print(name, w, h, os.path.getsize(png) // 1024, "KB")
