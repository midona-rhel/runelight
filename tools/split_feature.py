#!/usr/bin/env python3
"""Split methods out of LuminaPlugin into a feature class that holds `plugin`.

  tools/split_feature.py <package> <Class> <fieldName> "<javadoc sentence>" method[=newName] ...

Each method is named by the text of its declaration line up to the opening parenthesis, e.g. `private void updateWeather`
(the marker must occur once). The method moves with its comment block, renamed when `=newName` is given, made public,
and every call in the plugin becomes `<fieldName>.<newName>(...)` (method references `this::name` likewise). Plugin
instance members the moved code reads get `plugin.`; its statics come in by static import and are opened."""
import re, pathlib, sys
sys.path.insert(0, 'tools'); import split_plugin as sp

args = sys.argv[1:]
delegate = '--delegate' in args
args = [a for a in args if a != '--delegate']
pkg, cls_name, field, doc, *specs = args
lines = sp.PLUGIN.read_text().split('\n')
members = sp.plugin_members(lines)
methods = []
for spec in specs:
    marker, _, new = spec.partition('=')
    name = marker.split()[-1]
    methods.append((marker, name, new or name))
blocks = [(m + '(', 'method') for m, _, _ in methods]
for marker, _, _ in methods:
    hits = [l for l in lines if (marker + '(') in l]
    assert len(hits) == 1, (marker, len(hits))
region, remaining, at = sp.cut(lines, blocks)
moved = {n for _, n, _ in methods} | {nn for _, _, nn in methods}
params = set(); delegates = []
for marker, name, new in methods:
    decl = next(l for l in region.split('\n') if (marker + '(') in l)
    # the declaration may run over several lines: gather to the closing parenthesis
    rl = region.split('\n'); k = rl.index(decl); sig = decl
    while sig.count('(') > sig.count(')'):
        k += 1; sig += ' ' + rl[k].strip()
    arglist = sig[sig.find('(') + 1 : sig.rfind(')')]
    names = [a.strip().split()[-1] for a in re.split(r',(?![^<]*>)', arglist) if a.strip()]
    params |= set(names)
    ret = re.sub(r'^\t(?:private |protected |public )?(?:static )?(?:final )?(?:synchronized )?', '', sig[: sig.find(name + '(')]).strip()
    head = re.sub(r'^\t(?:private |protected |public )?', '\tpublic ', sig[: sig.rfind(')') + 1])
    body = f'{field}.{new}({", ".join(names)});'
    delegates.append('\t@Override\n' + head + '\n\t{\n\t\t' + ('' if ret == 'void' else 'return ') + body + '\n\t}\n')
    newdecl = re.sub(r'^(\t)(?:private |protected |public )?', r'\1public ', decl).replace(name + '(', new + '(', 1)
    region = region.replace(decl, newdecl, 1)
    if new != name:
        region = re.sub(r'(?<![\w.])' + re.escape(name) + r'\(', new + '(', region)
region = re.sub(r'^\t@Override\n', '', region, flags=re.M)  # the moved methods implement nothing in their new class
region, inst = sp.prefix(region, members, moved, extra_locals=tuple(params))
statics = sp.statics_used(region, members)
remaining = sp.open_statics(remaining, statics)
imports = sp.imports_of(lines)
header = f'package com.midonarhel.lumina.{pkg};\n\n' + '\n'.join(i for i in imports if f'.{pkg}.' not in i) + '\nimport com.midonarhel.lumina.LuminaPlugin;\nimport com.midonarhel.lumina.LuminaConfig;\nimport com.midonarhel.lumina.LuminaPlugin.*;\nimport static com.midonarhel.lumina.LuminaPlugin.*;\n\n'
cls = header + f'''/**
 * {doc} Split out of the plugin class verbatim; it reads the plugin's frame state through {{@code plugin}}.
 */
public final class {cls_name}
{{
	private final LuminaPlugin plugin;

	public {cls_name}(LuminaPlugin plugin)
	{{
		this.plugin = plugin;
	}}

''' + region + '}\n'
pathlib.Path(f'src/main/java/com/midonarhel/lumina/{pkg}/{cls_name}.java').write_text(cls)
remaining[at:at] = [f'\t// {doc.split(".")[0]} ({cls_name})', f'\tpublic final {cls_name} {field} = new {cls_name}(this);'] + (['\n'.join(delegates)] if delegate else [])
s = '\n'.join(remaining)
if not delegate:
    for _, name, new in methods:
        s = re.sub(r'(?<![\w.])' + re.escape(name) + r'\(', f'{field}.{new}(', s)
        s = s.replace(f'this::{name}', f'{field}::{new}')
for f in pathlib.Path('src/main/java/com/midonarhel/lumina').rglob('*.java'):
    if f == sp.PLUGIN or f.name == f'{cls_name}.java': continue
    t = f.read_text(); t2 = t
    for _, name, new in methods:
        t2 = re.sub(r'\bplugin\.' + re.escape(name) + r'\(', f'plugin.{field}.{new}(', t2)
    if t2 != t: f.write_text(t2); print('callers redirected in', f.name)
imp = f'import com.midonarhel.lumina.{pkg}.{cls_name};'
if imp not in s:
    s = re.sub(r'(^import com\.midonarhel\.lumina\.[^\n]+\n)', r'\1' + imp + '\n', s, count=1, flags=re.M)
sp.PLUGIN.write_text(s)
print(f'plugin {s.count(chr(10))} lines; {cls_name} {cls.count(chr(10))} lines; prefixed {len(inst)}; statics opened {len(statics)}')
