import base64, io, json, colorsys
import numpy as np
from PIL import Image
U='/mnt/user-data/uploads/'
pics=[
 ('wave','galaxy-tab-s8-3840x3840-7524.JPG','Ember Sand',['rise','wave','bars']),
 ('smoke','non-incense-smell-dark-52732.jpeg','Blue Smoke',['wave','shatter','tunnel']),
 ('nebula','36e508d78af96f0523f3db203ac5d6f0.jpg','Quiet Nebula',['warp','tunnel','rise']),
 ('galaxy','468200.jpg','Andromeda',['warp','radial','tunnel']),
 ('paint','abstract_mir_3840x2160.jpg','Paint Splash',['bars','rise','kaleido']),
 ('orbs','background_dark_spots_lines_51861_3840x2160.jpg','Violet Orbs',['fractal','kaleido','warp']),
 ('swirl','dark_colors_patterns_backgrounds_10561_3840x2400.jpg','Frost Spiral',['fractal','tunnel','warp']),
 ('green','fraxflame_5K_retina_green.jpg','Green Orbit',['radial','wave','shatter']),
 ('tunnel','hRXkDn.jpg','Blue Tunnel',['tunnel','radial','bars']),
 ('flame','light-from-a-lighter-dark-hd-wallpaper_5120x3200.jpg','The Lighter',['rise','bars','shatter']),
 ('ring','r-circle_8k.jpg','Red Circle',['tunnel','warp','shatter']),
 ('spheres','Samsung_Wallpaper_02.jpg','Two Spheres',['warp','fractal','radial']),
 ('rain','XRThCA.jpg','Night Rain',['rise','shatter','bars']),
]
def b64(data,mime='image/jpeg'): return f'data:{mime};base64,'+base64.b64encode(data).decode()
def thumb(im):
    t=im.convert('RGB'); t.thumbnail((360,240)); b=io.BytesIO(); t.save(b,'JPEG',quality=72); return b64(b.getvalue())
def palette(im):
    a=np.asarray(im.convert('RGB').resize((320,180)),dtype=float).reshape(-1,3)/255
    l=a@[.299,.587,.114]; a=a[l>np.quantile(l,0.85)]
    # weight saturated pixels
    rng=np.random.default_rng(1); c=a[rng.choice(len(a),4,replace=False)]
    for _ in range(25):
        d=((a[:,None]-c[None])**2).sum(-1); k=d.argmin(1)
        c=np.array([a[k==i].mean(0) if (k==i).any() else c[i] for i in range(4)])
    out=[]
    for r,g,b in c:
        h,s,v=colorsys.rgb_to_hsv(r,g,b); s=min(1,s*1.35+0.05); v=1.0
        out.append(colorsys.hsv_to_rgb(h,s,v))
    out.sort(key=lambda x: colorsys.rgb_to_hsv(*x)[1], reverse=True)  # most saturated = bass
    return [[round(x,3) for x in p] for p in out]
out=[]
for pid,f,name,styles in pics:
    im=Image.open(U+f)
    out.append(dict(id=pid,name=name,styles=styles,pal=palette(im),src=b64(open(U+f,'rb').read()),thumb=thumb(im)))
    print(name, out[-1]['pal'])
import math
from PIL import ImageDraw, ImageFilter
def gen_thumb(kind):
    im=Image.new('RGB',(360,203),(0,0,0)); dr=ImageDraw.Draw(im)
    if kind=='ember':
        pts=[(x, 110+28*math.sin(x/40)+10*math.sin(x/13)) for x in range(40,320)]
        dr.line(pts,fill=(255,120,40),width=3); dr.line([(x,y+8) for x,y in pts],fill=(50,80,255),width=2)
    elif kind=='ink':
        for i in range(60): dr.ellipse((180+i*2-40,60+math.sin(i/5)*30,220+i*2-40,100+math.sin(i/5)*30),outline=(90,130,200))
    else:
        dr.ellipse((130,40,250,160),outline=(255,90,40),width=3); dr.arc((130,40,250,160),150,280,fill=(30,150,190),width=3)
    im=im.filter(ImageFilter.GaussianBlur(1.2)); b=io.BytesIO(); im.save(b,'JPEG',quality=80); return b64(b.getvalue())
out += [
 dict(id='ember',name='Ember Line',gen=1,styles=['wave','rise','warp'],pal=[[1,0.3,0.05],[1,0.48,0.14],[0.18,0.32,1],[1,0.8,0.55]],thumb=gen_thumb('ember')),
 dict(id='ink',name='Ink Smoke',gen=2,styles=['warp','fractal','tunnel'],pal=[[0.12,0.3,0.8],[0.25,0.5,1],[0.55,0.75,1],[0.85,0.92,1]],thumb=gen_thumb('ink')),
 dict(id='halo',name='Halo',gen=3,styles=['warp','radial','fractal'],pal=[[1,0.3,0.12],[0.08,0.6,0.8],[1,0.55,0.3],[0.9,0.95,1]],thumb=gen_thumb('halo')),
]
tpl=open('noct/template2.html').read()
open('noct/Nocturne.html','w').write(tpl.replace('/*__DATA__*/[]', json.dumps(out).replace('<','\\u003c')))
