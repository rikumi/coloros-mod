from pathlib import Path
import re,io
from PIL import Image
import numpy as np
from scipy import ndimage
import cairosvg
s=Path('docs/assets/messages-monochrome.svg').read_text()
paths=re.findall(r'd="([^"]+)"',s)
assert len(paths)==2
for p in paths: assert set(re.findall('[a-zA-Z]',p)) <= set('MHALVZ')
def render(path,view='-8 -8 144 144',size=1024):
 b=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}"><path fill="white" d="{path}"/></svg>'
 return np.array(Image.open(io.BytesIO(cairosvg.svg2png(bytestring=b.encode(),output_width=size,output_height=size))).convert('RGBA'))[:,:,3]>127
f,b=[render(p) for p in paths]
assert not (f&b).any()
gap=ndimage.distance_transform_edt(~f)[b].min()/(1024/144)
assert gap>8.8,gap
yy,xx=np.where(f|b)
padding=min(xx.min(),yy.min(),1023-xx.max(),1023-yy.max())/1024*24
assert padding>1.9,padding
_,n=ndimage.label(f|b);assert n==2,n
ref=np.array(Image.open('docs/assets/messages-foreground-threshold.png'))>127
fit=render(paths[0],'-26 -26 180 180',180)
iou=(ref&fit).sum()/(ref|fit).sum()
assert iou>.97,iou
print(f'PASS: 2 disconnected bubbles, no overlap, gap {gap:.2f} source pixels, padding {padding:.2f}dp; foreground threshold IoU {iou:.4f}')
