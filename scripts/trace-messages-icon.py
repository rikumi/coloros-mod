#!/usr/bin/env python3
"""Trace ColorOS Messages 16.60.10's original launcher artwork.
Requires Pillow, numpy, scipy and cairosvg. Pass the extracted PNG.
The SVG uses only line segments and circular arcs, with a transparent cutout.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw
import numpy as np
from scipy import ndimage
import cairosvg

source = Path(sys.argv[1])
out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('docs/assets')
out.mkdir(parents=True, exist_ok=True)
im = Image.open(source).convert('RGBA')
pixels = np.array(im)
white = pixels[:, :, 0].astype(float) / np.maximum(pixels[:, :, 1], 1)
def mask(threshold):
    labels, _ = ndimage.label((white >= threshold) & (pixels[:, :, 3] > 240))
    sizes = np.bincount(labels.flat); sizes[0] = 0
    return labels == sizes.argmax()
front, both = mask(.66), mask(.50)
for name, m in [('foreground', front), ('both-bubbles', both), ('background-only', both & ~front)]:
    Image.fromarray((m * 255).astype('uint8')).save(out / ('messages-' + name + '-threshold.png'))

# Coordinates measured from threshold contours, in source-image pixels:
# front bounds (30,30)-(123,105), r=16, lower-left tail to y=120;
# rear bounds (57,59)-(150,134), r=16, lower-right tail to y=149.
# Coordinates retain source origin at 26px; a -8/-8/144/144 viewBox
# gives the 120x119px glyph approximately 2dp outer padding at 24dp.
# Rear subtracts the front expanded by 9px (1.5dp at 24dp).
# Its inner concave arc has radius 16+9=25.
foreground = ('M20 4 H81 A16 16 0 0 1 97 20 V63 '
              'A16 16 0 0 1 81 79 H36 L6.9 93.5 '
              'A2 2 0 0 1 4 91.7 V20 A16 16 0 0 1 20 4 Z')
rear = ('M106 33 H108 A16 16 0 0 1 124 49 V120.7 '
        'A2 2 0 0 1 121.1 122.5 L92 108 H47 '
        'A16 16 0 0 1 31 92 V91.55 L38.12 88 H81 '
        'A25 25 0 0 0 106 63 Z')
paths = [foreground, rear]
svg = '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="-8 -8 144 144">\n' + ''.join(
    f'  <path fill="#ffffff" d="{p}"/>\n' for p in paths) + '</svg>\n'
(out / 'messages-monochrome.svg').write_text(svg)
vector = '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="144" android:viewportHeight="144">\n    <group android:translateX="8" android:translateY="8">\n' + ''.join(
    f'    <path android:fillColor="#FFFFFFFF" android:pathData="{p}" />\n' for p in paths) + '    </group>\n</vector>\n'
Path('app/src/main/res/drawable/ic_messages_monochrome.xml').write_text(vector)
cairosvg.svg2png(bytestring=svg.encode(), write_to=str(out / 'messages-monochrome.png'), output_width=512, output_height=512)
# Review source, both thresholds, and actual final SVG render on dark/light.
preview = Image.new('RGB', (1000, 440), '#eeeef2')
draw = ImageDraw.Draw(preview)
for i, (label, img) in enumerate([
    ('Original 16.60.10', im),
    ('Strong white >= 0.66', Image.open(out / 'messages-foreground-threshold.png')),
    ('White >= 0.50', Image.open(out / 'messages-both-bubbles-threshold.png')),
    ('Final SVG / dark', Image.open(out / 'messages-monochrome.png')),
    ('Final SVG / light', Image.open(out / 'messages-monochrome.png')),
]):
    x = i * 200
    bg = Image.new('RGBA', (184, 184), '#24262b' if i < 4 else '#ffffff')
    thumb = img.convert('RGBA').resize((164,164), Image.Resampling.LANCZOS)
    if i == 4:
        thumb.paste('#202226', (0,0,164,164), thumb.getchannel('A'))
    bg.alpha_composite(thumb, (10,10))
    preview.paste(bg.convert('RGB'), (x+8,38))
    draw.text((x+8,16),label,fill='#222222')
# Small actual-size masks at 16,20,24,32,48px against a dark panel.
draw.rectangle((8,250,992,428),fill='#24262b')
draw.text((24,265),'Final SVG at 16 / 20 / 24 / 32 / 48 px',fill='white')
for i, size in enumerate([16,20,24,32,48]):
    import io
    small=Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(),output_width=size,output_height=size))).convert('RGBA')
    preview.paste(small,(32+i*110,315),small)
    draw.text((32+i*110,380),str(size),fill='white')
preview.save(out / 'messages-monochrome-preview.png')
print('Source:',source,'; thresholds 0.66 / 0.50; areas',front.sum(),both.sum())
print('SVG:',out / 'messages-monochrome.svg')
