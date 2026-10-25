"""Trace and compare the current framework stat_notify_more bitmap (argument 1)."""
from pathlib import Path
import sys, io, os
os.environ.setdefault('DYLD_FALLBACK_LIBRARY_PATH', '/opt/homebrew/lib')
from PIL import Image, ImageDraw
import numpy as np
import cairosvg
path = ('M20,9 L63,9 A5,5 0,0 1,68,14 L68,57 A5,5 0,0 1,63,62 '
        'L20,62 Q17,62 15.5,59 L4.5,39 Q3,36 4.5,33 L15.5,12 Q17,9 20,9 Z '
        'M35,22 L41,22 L41,33 L51,33 L51,39 L41,39 L41,49 L35,49 L35,39 L25,39 L25,33 L35,33 Z')
svg = '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 72 72"><path fill="white" fill-rule="evenodd" d="'+path+'"/></svg>\n'
source = Image.open(sys.argv[1]).convert('RGBA')
render = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=72, output_height=72))).convert('RGBA')
a=np.array(source)[:,:,3]>128; b=np.array(render)[:,:,3]>128
iou=(a&b).sum()/(a|b).sum()
assert iou>.97, iou
out=Path('docs/assets'); out.mkdir(exist_ok=True)
(out/'test-notification-monochrome.svg').write_text(svg)
preview=Image.new('RGBA',(600,320),'#333333')
preview.alpha_composite(source.resize((288,288)),(4,16)); preview.alpha_composite(render.resize((288,288)),(308,16))
preview.save(out/'test-notification-monochrome-preview.png')
Path('app/src/main/res/drawable/ic_test_notification_monochrome.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<!-- Traced from framework stat_notify_more, with its transparent plus retained. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="72" android:viewportHeight="72">
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="'''+path+'''" />
</vector>
''')
print('PASS: native test icon alpha-mask IoU', iou)
