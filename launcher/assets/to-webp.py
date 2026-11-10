"""Turns the PNGs from render-icons.js into WebP: much smaller, and smooth gradients stay smooth.
Run from the launcher folder after render-icons.js: python3 assets/to-webp.py"""
import glob
import os
from PIL import Image

files = glob.glob('app/src/main/res/mipmap-*/ic_launcher_*.png') + glob.glob('app/src/main/res/drawable-*/banner.png')
for path in files:
    image = Image.open(path).convert('RGBA')
    out = path[:-4] + '.webp'
    if 'monochrome' in path:
        image.save(out, 'WEBP', lossless=True, method=6)  # one colour: lossless is tiny
    else:
        image.save(out, 'WEBP', quality=92, alpha_quality=100, method=6)
    print(f'{out}: {os.path.getsize(path) // 1024} KB -> {os.path.getsize(out) // 1024} KB')
    os.remove(path)
