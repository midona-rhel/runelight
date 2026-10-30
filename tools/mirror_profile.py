"""Mirror the user's 117 HD settings (hd.<key>) into Lumina's (lumina.<key>) for the keys Lumina ported, so the
side-by-side runs both renderers on the same settings. Usage: mirror_profile.py <profile.properties> key1 key2 ..."""
import re, sys
p = sys.argv[1]
keys = sys.argv[2:]
s = open(p, encoding='utf-8').read()
hd = dict(re.findall(r'^hd\.([A-Za-z0-9_]+)=(.*)$', s, re.M))
out = s
changed = []
for k in keys:
    if k not in hd:
        print('no hd value for', k); continue
    v = hd[k]
    if re.search(r'^lumina\.' + re.escape(k) + r'=.*$', out, re.M):
        out = re.sub(r'^lumina\.' + re.escape(k) + r'=.*$', 'lumina.' + k + '=' + v, out, flags=re.M)
    else:
        out = out.rstrip('\n') + '\nlumina.' + k + '=' + v + '\n'
    changed.append((k, v))
open(p, 'w', encoding='utf-8', newline='').write(out)
for k, v in changed:
    print('lumina.' + k + '=' + v)
