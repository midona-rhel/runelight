"""The swap capture's numbers (::runelight swapcapture, eight frames around a scene swap as swap-k<i>-s<frames since
swap>.png): for each frame its mean luma with the UI masked out, the share of pixels that differ by more than 8 levels
from the frame before, and from the last frame (the steady state). A pop shows as a frame that differs from both of
its neighbours where the scene did not change. Usage: uv run --with pillow --with numpy tools/swap_diff.py [dir]"""
import sys, os, glob, re
import numpy as np
from PIL import Image

d = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/.runelite/lumina/captures')

def load(p):
    im = np.asarray(Image.open(p).convert('RGB')).astype(np.float32)
    h, w = im.shape[:2]
    mask = np.ones((h, w), bool)
    mask[int(h*0.78):, :int(w*0.70)] = False      # chat box
    mask[:int(h*0.26), int(w*0.70):] = False      # minimap
    mask[int(h*0.86):, int(w*0.68):] = False      # sidebar icons
    mask[:int(h*0.14), :int(w*0.20)] = False      # overlays top-left
    return im, mask

def luma(x):
    return 0.2126*x[..., 0] + 0.7152*x[..., 1] + 0.0722*x[..., 2]

files = sorted(glob.glob(f'{d}/swap-k*-s*.png'), key=lambda p: int(re.search(r'swap-k(\d+)', p).group(1)))
frames = [(os.path.basename(p)[:-4], *load(p)) for p in files]
last_im, last_mask = frames[-1][1], frames[-1][2]
prev = None
print(f'{"frame":<14} {"luma":>6} {"Δprev>8":>8} {"Δlast>8":>8}')
for name, im, mask in frames:
    L = luma(im)
    dp = '' if prev is None else f'{(np.abs(L - luma(prev))[mask] > 8).mean()*100:7.1f}%'
    dl = f'{(np.abs(L - luma(last_im))[mask] > 8).mean()*100:7.1f}%'
    print(f'{name:<14} {L[mask].mean():6.1f} {dp:>8} {dl:>8}')
    prev = im
