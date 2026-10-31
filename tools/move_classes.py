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
# each moved class's own test moves with it, keeping package-private access: <Class>Test.java
(troot / sub).mkdir(exist_ok=True)
for c in classes:
    t = troot / f'{c}Test.java'
    if not t.exists(): continue
    dst = troot / sub / f'{c}Test.java'
    subprocess.check_call(['git', 'mv', str(t), str(dst)])
    s = dst.read_text()
    s = re.sub(r'^package com\.midonarhel\.runelight;', f'package {target};', s, count=1, flags=re.M)
    s = re.sub(r'(^package [^\n]+\n)', r'\1\nimport ' + base + '.*;\n', s, count=1, flags=re.M)
    dst.write_text(s)
# static imports of the moved classes point at the new package
static_import = re.compile(r'^import (?:static )?com\.midonarhel\.runelight\.(' + '|'.join(map(re.escape, classes)) + r')\.', re.M)
for d in [root, troot]:
    for f in d.rglob('*.java'):
        s = f.read_text(); s2 = static_import.sub(lambda m: m.group(0).replace('com.midonarhel.runelight.' + m.group(1) + '.', target + '.' + m.group(1) + '.'), s)
        if s2 != s: f.write_text(s2)
# explicit imports of the moved classes from the old package (subpackages had them) point at the new one
old_import = re.compile(r'^(import (?:static )?)com\.midonarhel\.runelight\.(' + '|'.join(map(re.escape, classes)) + r')(\.[\w.*]+;|;)', re.M)
for d in [root, troot]:
    for f in d.rglob('*.java'):
        s = f.read_text(); s2 = old_import.sub(lambda m: f'{m.group(1)}{target}.{m.group(2)}{m.group(3)}', s)
        if s2 != s: f.write_text(s2)
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
