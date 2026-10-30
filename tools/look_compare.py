"""The look-reference numbers: for every capture of the set (tools/capture_set.sh) present for two renderers,
the sky band's and the ground band's mean colour and luma, the scene's luma percentiles and mean saturation,
with the UI masked out. Usage: look_compare.py <dir> <tagA> <tagB>  (e.g. captures old lum)."""
import sys, os, glob
import numpy as np
from PIL import Image

d, ta, tb = sys.argv[1], sys.argv[2], sys.argv[3]

def load(p):
    im = np.asarray(Image.open(p).convert('RGB')).astype(np.float32) / 255
    h, w = im.shape[:2]
    mask = np.ones((h, w), bool)
    mask[int(h*0.78):, :int(w*0.70)] = False      # chat box
    mask[:int(h*0.26), int(w*0.70):] = False      # minimap
    mask[int(h*0.86):, int(w*0.68):] = False      # sidebar icons
    mask[:int(h*0.14), :int(w*0.20)] = False      # overlays top-left
    return im, mask

def luma(x):
    return 0.2126*x[..., 0] + 0.7152*x[..., 1] + 0.0722*x[..., 2]

def sat(x):
    mx = x.max(axis=-1); mn = x.min(axis=-1)
    return np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)

def stats(p):
    im, mask = load(p); h, w = mask.shape
    L = luma(im)
    sky = np.zeros_like(mask); sky[int(h*0.05):int(h*0.22), int(w*0.25):int(w*0.70)] = True; sky &= mask
    gnd = np.zeros_like(mask); gnd[int(h*0.58):int(h*0.76), int(w*0.30):int(w*0.66)] = True; gnd &= mask
    def rgb(m): return im[m].mean(axis=0) * 255
    Lm = L[mask]
    return dict(sky=rgb(sky), skyL=L[sky].mean()*255, gnd=rgb(gnd), gndL=L[gnd].mean()*255,
                p10=np.percentile(Lm, 10)*255, p50=np.percentile(Lm, 50)*255, p90=np.percentile(Lm, 90)*255,
                sat=sat(im)[mask].mean())

names = sorted(set(os.path.basename(p)[len(ta)+1:-4] for p in glob.glob(f'{d}/{ta}-*.png')))
print(f'{"capture":<12} {"rend":<4} {"sky RGB":<16} {"skyL":>5} {"ground RGB":<16} {"gndL":>5} {"p10":>5} {"p50":>5} {"p90":>5} {"sat":>5}')
for n in names:
    for t in (ta, tb):
        p = f'{d}/{t}-{n}.png'
        if not os.path.exists(p): continue
        s = stats(p)
        f = lambda v: ' '.join(f'{c:3.0f}' for c in v)
        print(f'{n:<12} {t:<4} {f(s["sky"]):<16} {s["skyL"]:5.0f} {f(s["gnd"]):<16} {s["gndL"]:5.0f} {s["p10"]:5.0f} {s["p50"]:5.0f} {s["p90"]:5.0f} {s["sat"]:5.2f}')
