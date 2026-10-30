#!/usr/bin/env python3
"""Read javac's output on stdin and make the package-private classes and members it names public. Mechanical, for
moves between packages; re-run compile afterwards. Usage: ./gradlew -q compileJava 2>&1 | tools/open_visibility.py"""
import re, sys, pathlib
root = pathlib.Path('src/main/java/com/midonarhel/runelight')
files = {p.stem: p for p in root.rglob('*.java')}
changed = set()
def publicize_class(name):
    p = files.get(name)
    if not p: return
    s = p.read_text()
    s2 = re.sub(r'^(\s*)((?:abstract |final |static )*)(class|interface|enum) ' + re.escape(name) + r'\b', r'\1public \2\3 ' + name, s, count=1, flags=re.M)
    if s2 != s: p.write_text(s2); changed.add(f'{name} (class)')
def publicize_nested(cls, nested):
    """A nested class/enum/interface/record `nested` inside `cls`'s file, at any depth."""
    p = files.get(cls)
    if not p: return False
    s = p.read_text()
    s2 = re.sub(r'^(\t+)((?:abstract |final |static )*)(class|interface|enum|record) ' + re.escape(nested) + r'\b', r'\1public \2\3 ' + nested, s, count=1, flags=re.M)
    if s2 != s: p.write_text(s2); changed.add(f'{cls}.{nested} (nested)'); return True
    return False

def publicize_member(cls, member):
    p = files.get(cls)
    if not p: return
    s = p.read_text(); lines = s.split('\n')
    pat = re.compile(r'^(\t+)((?:static |final |synchronized |abstract |volatile |transient )*)([\w<>\[\], ?.]+\s+)?' + re.escape(member) + r'\s*(\(|=|;|,)')
    for i, l in enumerate(lines):
        if l.lstrip().startswith(('public', 'private', 'protected', '//', '*', '@', 'return', 'if', 'for', 'while')): continue
        m = pat.match(l)
        # a declaration has a type before the name (or is a method call-shaped declaration at class depth);
        # a bare `name = ...` deeper in is an assignment inside a constructor, never touched
        if m and (m.group(3) or (m.group(4) == '(' and len(m.group(1)) == 1)) and len(m.group(1)) <= 2 and not re.match(r'^\t+(return|new|throw|else|case)\b', l):
            lines[i] = m.group(1) + 'public ' + l[len(m.group(1)):]
            changed.add(f'{cls}.{member}'); break
    else:
        # a constructor
        for i, l in enumerate(lines):
            if re.match(r'^\t' + re.escape(member) + r'\s*\(', l):
                lines[i] = '\tpublic ' + l[1:]; changed.add(f'{cls}.{member}()'); break
    p.write_text('\n'.join(lines))
lines_in = sys.stdin.read().split('\n')
for i, l in enumerate(lines_in):
    m = re.search(r'symbol:\s+(?:class|variable) (\w+)$', l)
    if m and m.group(1) in files and i > 0 and 'cannot find symbol' in lines_in[i-1]:
        publicize_class(m.group(1))
for line in lines_in:
    m = re.search(r'error: (\w+) is not public in ([\w.]+); cannot be accessed', line)
    if m:
        name, where = m.group(1), m.group(2).split('.')[-1]
        if where in files and name in files and where not in files.get(name).read_text()[:0]: pass
        # a class (where = its package) or a member (where = its class)
        if name in files and where not in files: publicize_class(name)
        elif where in files:
            if name in files: publicize_class(name)
            elif not publicize_nested(where, name): publicize_member(where, name)
        continue
    m = re.search(r'error: (\w+)\(.*?\) is not public in (\w+)', line) or re.search(r'error: (\w+)\([^)]*\) (?:has private access|is not public) in (\w+)', line)
    if m: publicize_member(m.group(2), m.group(1)); continue
    m = re.search(r'error: package (\w+) does not exist', line)
    if m: publicize_class(m.group(1)); continue
    m = re.search(r'error: (\w+)\(\) is not public in (\w+)', line)
    if m: publicize_member(m.group(2), m.group(1))
print('opened:', ', '.join(sorted(changed)) or 'nothing')
