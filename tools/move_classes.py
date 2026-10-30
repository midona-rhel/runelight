#!/usr/bin/env python3
"""Move Java classes of com.midonarhel.runelight into a subpackage: git mv, package line, imports in every file
that names them (main and test). Usage: tools/move_classes.py <subpackage> Class [Class...]. Visibility is left to
the compiler: run ./gradlew compileJava compileTestJava afterwards and open what it names."""
import re, subprocess, sys, pathlib
sub, classes = sys.argv[1], sys.argv[2:]
root = pathlib.Path('src/main/java/com/midonarhel/runelight'); troot = pathlib.Path('src/test/java/com/midonarhel/runelight')
base = 'com.midonarhel.runelight'; target = f'{base}.{sub}'
(root / sub).mkdir(exist_ok=True)
moved = set(classes)
for c in classes:
    src = root / f'{c}.java'; dst = root / sub / f'{c}.java'
    subprocess.check_call(['git', 'mv', str(src), str(dst)])
    s = dst.read_text()
    s = re.sub(r'^package com\.midonarhel\.runelight;', f'package {target};', s, count=1, flags=re.M)
    # the parent package's classes it used without import
    s = re.sub(r'(^package [^\n]+\n)', r'\1\nimport ' + base + '.*;\n', s, count=1, flags=re.M)
    dst.write_text(s)
word = re.compile(r'\b(' + '|'.join(map(re.escape, classes)) + r')\b')
touched = []
for d in [root, troot]:
    for f in d.rglob('*.java'):
        if f.parent == root / sub: continue
        s = f.read_text()
        pkg = re.search(r'^package ([\w.]+);', s, re.M).group(1)
        if pkg == target: continue
        used = sorted({m for m in word.findall(re.sub(r'//[^\n]*|/\*.*?\*/', '', s, flags=re.S)) if f'import {target}.{m};' not in s})
        if not used: continue
        imports = ''.join(f'import {target}.{u};\n' for u in used)
        s = re.sub(r'(^package [^\n]+\n)', r'\1\n' + imports, s, count=1, flags=re.M)
        f.write_text(s); touched.append(str(f.relative_to('src')))
print('moved', len(classes), 'imports added in', len(touched), 'files:', ' '.join(touched))
