"""Compare two renderer captures: per-region mean colour difference, a diff image, and a side-by-side."""
import sys
from PIL import Image, ImageChops
import numpy as np

a_path, b_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
a = Image.open(a_path).convert('RGB')
b = Image.open(b_path).convert('RGB')
if a.size != b.size:
    print('size mismatch', a.size, b.size); sys.exit(1)
A = np.asarray(a).astype(np.int16); B = np.asarray(b).astype(np.int16)
d = np.abs(A - B)
h, w = d.shape[:2]
# ignore the UI: chat box bottom-left, minimap top-right, sidebar
mask = np.ones((h, w), bool)
mask[int(h*0.80):, :int(w*0.22)] = False
mask[:int(h*0.25), int(w*0.80):] = False
mask[:, int(w*0.92):] = False
dm = d[mask]
print(f'mean abs diff RGB {dm.mean():.2f}  p50 {np.percentile(dm.max(axis=1),50):.0f}  p90 {np.percentile(dm.max(axis=1),90):.0f}  p99 {np.percentile(dm.max(axis=1),99):.0f}')
print(f'pixels within 4: {(dm.max(axis=1)<=4).mean()*100:.1f}%   within 12: {(dm.max(axis=1)<=12).mean()*100:.1f}%')
# 4x4 grid of regions
for gy in range(4):
    row = []
    for gx in range(4):
        y0, y1, x0, x1 = gy*h//4, (gy+1)*h//4, gx*w//4, (gx+1)*w//4
        rm = mask[y0:y1, x0:x1]
        if rm.sum() == 0:
            row.append('  ui ')
            continue
        rd = d[y0:y1, x0:x1][rm]
        sa = A[y0:y1, x0:x1][rm].mean(axis=0); sb = B[y0:y1, x0:x1][rm].mean(axis=0)
        row.append(f'{rd.mean():4.1f} ({sa[0]-sb[0]:+.0f},{sa[1]-sb[1]:+.0f},{sa[2]-sb[2]:+.0f})')
    print('  '.join(row))
diff = Image.fromarray((np.clip(d * 4, 0, 255)).astype(np.uint8))
side = Image.new('RGB', (w*2, h*2))
side.paste(a, (0, 0)); side.paste(b, (w, 0)); side.paste(diff, (0, h))
side.paste(Image.fromarray((np.abs(A - B).mean(axis=2) > 12).astype(np.uint8) * 255).convert('RGB'), (w, h))
side.thumbnail((2400, 2400))
side.save(out)
print('wrote', out)
