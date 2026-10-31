#!/usr/bin/env python3
"""Cut blocks out of LuminaPlugin.java into a new class that holds `plugin` and reads the plugin's state through it.
Used as a module from a short driver (see the Split commits). Blocks are (start_marker, kind): kind 'method' cuts the
method whose declaration line contains the marker, with the comment block above it, to its own closing brace;
kind 'range' cuts from the line containing the marker to the first later line containing the second marker (inclusive)."""
import re, pathlib

PLUGIN = pathlib.Path('src/main/java/com/midonarhel/lumina/LuminaPlugin.java')
LOCAL_DECL = re.compile(r'\b(?:int|float|long|boolean|double|byte|short|char|var|[A-Z]\w*(?:<[^>]*>)?(?:\[\])*)\s+(\w+)\s*(?:=|;|,|\)|:)')
COMMENT = re.compile(r'^\s*(//|\*|/\*)')

def cut(lines, blocks):
    """Return (region_text, remaining_lines, insert_index) for the blocks, cut in file order."""
    spans = []
    for marker, kind, *rest in blocks:
        i = next(k for k, l in enumerate(lines) if marker in l)
        if kind == 'method':
            s = i
            while s > 0 and (COMMENT.match(lines[s - 1]) or lines[s - 1].strip().startswith('@')):
                s -= 1
            e = next(k for k in range(i, len(lines)) if lines[k] == '\t}')
        else:
            s = i
            e = next(k for k in range(i, len(lines)) if rest[0] in lines[k])
        spans.append((s, e))
    spans.sort()
    region = []
    for s, e in spans:
        region.extend(lines[s:e + 1]); region.append('')
    remaining = []; last = 0
    for s, e in spans:
        remaining.extend(lines[last:s]); last = e + 1
    remaining.extend(lines[last:])
    return '\n'.join(region), remaining, spans[0][0]

def plugin_members(lines):
    """name -> True if static, for the plugin's own fields and methods declared at class depth."""
    members = {}
    decl = re.compile(r'^\t(?:public |private |protected )?((?:static )?)(?:final )?(?:volatile )?(?:transient )?(?:synchronized )?([\w<>\[\], ?.]+?)\s+(\w+)\s*(\(|=|;|,)')
    for l in lines:
        m = decl.match(l)
        if not m or m.group(2) in ('return', 'new', 'throw', 'else', 'case', 'import', 'package') or m.group(2).startswith(('class', 'interface', 'enum')):
            continue
        names = [m.group(3)]
        if m.group(4) in (',', '=', ';'):
            # a declaration list: all the names on the line
            names = re.findall(r'(\w+)\s*(?:=[^,;]*)?(?:,|;)', l[len(m.group(1)) :]) or names
            names = [n for n in names if re.match(r'^[a-z_]\w*$|^[A-Z_][A-Z0-9_]*$', n)]
        for n in names:
            members[n] = bool(m.group(1).strip())
    return members

def prefix(region, members, moved, extra_locals=()):
    """`plugin.` before the plugin's instance members the region uses, outside comments and strings."""
    locals_ = set(LOCAL_DECL.findall(region)) | set(extra_locals)
    for l in region.split('\n'):  # names in a declaration list: `int a, b, c;`
        m = re.match(r'^\t+(?:public |private |protected |static |final )*(?:int|float|long|boolean|double|byte|short|[A-Z][\w<>\[\].]*)\s+(\w+(?:\s*=[^,;]+)?(?:\s*,\s*\w+(?:\s*=[^,;]+)?)+)\s*;', l)
        if m: locals_ |= set(re.findall(r'(\w+)\s*(?:=|,|$)', m.group(1)))
    inst = [n for n, st in members.items() if not st and n not in moved and n not in locals_]
    pat = re.compile(r'(?<![\w.])\b(' + '|'.join(map(re.escape, sorted(inst, key=len, reverse=True))) + r')\b(?!\s*\()') if inst else None
    meth = re.compile(r'(?<![\w.])\b(' + '|'.join(map(re.escape, [n for n in inst])) + r')\s*\(') if inst else None
    out = []
    for l in region.split('\n'):
        if COMMENT.match(l) or re.match(r'^\t+(?:public |private |protected |static |final )+[\w<>\[\], ?.]+\s+\w+\s*(?:\(|;|,|=)', l):
            out.append(l); continue
        # protect string literals
        parts = re.split(r'("(?:\\.|[^"\\])*")', l)
        for k in range(0, len(parts), 2):
            seg = parts[k]
            code, sep, comment = seg.partition('//')
            if pat: code = pat.sub(r'plugin.\1', code)
            if meth: code = meth.sub(r'plugin.\1(', code)
            parts[k] = code + sep + comment
        out.append(''.join(parts))
    return '\n'.join(out), inst

def statics_used(region, members):
    return sorted(n for n, st in members.items() if st and re.search(r'(?<![\w.])\b' + re.escape(n) + r'\b', region))

def open_statics(lines, names):
    for i, l in enumerate(lines):
        for n in names:
            if re.match(r'^\tprivate static ', l) and re.search(r'\b' + re.escape(n) + r'\b', l):
                lines[i] = l.replace('\tprivate static ', '\tpublic static ', 1)
    return lines

def imports_of(lines):
    return [l for l in lines if l.startswith('import ')]
