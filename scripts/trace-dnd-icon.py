from PIL import Image,ImageDraw
import numpy as np
from scipy import ndimage,optimize
from pathlib import Path
import math,io,cairosvg,sys
im=Image.open(sys.argv[1]).convert('RGB');a=np.array(im);mask=a.min(2)>225
edge=mask & ~ndimage.binary_erosion(mask)
y,x=np.where(edge);pts=np.stack([x+.5,y+.5],axis=1)
def circle(p):
 c=optimize.least_squares(lambda v:np.linalg.norm(p-v[:2],axis=1)-v[2],[*p.mean(0),150]).x
 return c
outer=circle(pts[(pts[:,0]<240)|(pts[:,1]>400)])
innerpts=pts[np.abs(np.linalg.norm(pts-outer[:2],axis=1)-outer[2])>3]
inner=circle(innerpts)
# Boundary pixels lie half a pixel inside the white region.
outer[2] += .45
inner[2] -= .45
print('outer',outer,'inner',inner)
c0=outer[:2];r0=outer[2];c1=inner[:2];r1=inner[2];d=np.linalg.norm(c1-c0);v=(c1-c0)/d;along=(r0*r0-r1*r1+d*d)/(2*d);h=math.sqrt(r0*r0-along*along);mid=c0+v*along;perp=np.array([-v[1],v[0]])
p0=mid-perp*h;p1=mid+perp*h
# Top intersection to lower-right intersection takes the long outer arc,
# then the inner arc returns along the concave edge.
if p0[1]>p1[1]:p0,p1=p1,p0
# Normalize to a 24dp canvas, glyph diameter20dp and 2dp padding.
scale=20/(2*r0);origin=c0-r0
conv=lambda p:(p-origin)*scale+2
q0,q1=conv(p0),conv(p1);rr0=r0*scale;rr1=r1*scale
path=f'M{q0[0]:.5f} {q0[1]:.5f} A{rr0:.5f} {rr0:.5f} 0 1 0 {q1[0]:.5f} {q1[1]:.5f} A{rr1:.5f} {rr1:.5f} 0 0 1 {q0[0]:.5f} {q0[1]:.5f} Z'
svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path fill="#ffffff" d="{path}"/></svg>\n'
out=Path('docs/assets');(out/'dnd-monochrome.svg').write_text(svg)
Path('app/src/main/res/drawable/ic_dnd_monochrome.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n    <path android:fillColor="#FFFFFFFF" android:pathData="'+path+'" />\n</vector>\n')
render=Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(),output_width=312,output_height=312))).convert('RGBA')
preview=Image.new('RGB',(680,370),'#202226');preview.paste(im.resize((312,312)),(16,40));preview.paste(render,(352,40),render);draw=ImageDraw.Draw(preview);draw.text((20,15),'Original system artwork',fill='white');draw.text((356,15),'Traced circular-arc vector',fill='white');preview.save(out/'dnd-monochrome-preview.png')
# Compare the fitted two-circle geometry directly with source pixels.
yy,xx=np.mgrid[:mask.shape[0],:mask.shape[1]];points=np.stack([xx+.5,yy+.5],axis=-1)
fit=(np.linalg.norm(points-c0,axis=2)<=r0)&(np.linalg.norm(points-c1,axis=2)>=r1)
iou=(fit&mask).sum()/(fit|mask).sum();print('IoU',iou,path);assert iou>.99
