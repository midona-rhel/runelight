import re, pathlib, sys
sys.path.insert(0, 'tools'); import split_plugin as sp
lines = sp.PLUGIN.read_text().split('\n')
members = sp.plugin_members(lines)
blocks = [
  ('// The composite (composite_frag.glsl) is compiled once', 'range', 'compositeEdges, compositeMark};'),
  ('private void locateComposite(CompositeProgram c)', 'method'),
  ('private void useComposite(CompositeProgram c)', 'method'),
  ('private void scissorHalf(boolean withClouds, boolean withWater)', 'method'),
  ('private void compositeScene()', 'method'),
]
region, remaining, at = sp.cut(lines, blocks)
moved = {'CompositeProgram','compositeFull','compositeClouds','compositeWater','compositeCloudsWater','compositeEdges','compositeMark','composites','COMPOSITE_FULL','COMPOSITE_CLOUDS','COMPOSITE_WATER','COMPOSITE_CLOUDS_WATER','COMPOSITE_EDGES','COMPOSITE_MARK','locateComposite','useComposite','scissorHalf','compositeScene'}
region = region.replace('\tprivate void compositeScene()', '\tpublic void draw()').replace('\tprivate void locateComposite(CompositeProgram c)', '\tpublic void locate(CompositeProgram c)')
region = region.replace('\tprivate void useComposite(CompositeProgram c)', '\tprivate void use(CompositeProgram c)')
region = re.sub(r'(?<![\w.])useComposite\(', 'use(', region)
# the holder class and its members are read by the plugin's program code: public, inside that class block only
rl = region.split('\n')
i = next(k for k, l in enumerate(rl) if 'private static final class CompositeProgram' in l)
rl[i] = rl[i].replace('private static final class', 'public static final class')
j = i + 1
while rl[j] != '\t}':
    if re.match(r'^\t\t(?!public |private |protected |//|\*|/\*|@|\}|\{|this\.)\S', rl[j]):
        rl[j] = '\t\tpublic ' + rl[j][2:]
    j += 1
region = '\n'.join(rl)
region = re.sub(r'^\tprivate (static final int COMPOSITE_|final CompositeProgram)', r'\tpublic \1', region, flags=re.M)
region, inst = sp.prefix(region, members, moved, extra_locals=('c','p','pass','program'))
statics = sp.statics_used(region, members)
remaining = sp.open_statics(remaining, statics)
imports = sp.imports_of(lines)
header = 'package com.midonarhel.lumina.frame;\n\n' + '\n'.join(i for i in imports if '.frame.' not in i) + '\nimport com.midonarhel.lumina.LuminaPlugin;\nimport com.midonarhel.lumina.LuminaPlugin.*;\nimport static com.midonarhel.lumina.LuminaPlugin.*;\n\n'
cls = header + '''/**
 * The composite: the full-screen pass that lights the scene from the targets (and its half-resolution clouds/water
 * variants and the MSAA edge passes), one program per pass sharing uniforms set before each draws. Split out of the
 * plugin class verbatim; it reads the plugin's frame state through {@code plugin}.
 */
public final class Composite
{
	private final LuminaPlugin plugin;

	public Composite(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

''' + region + '}\n'
pathlib.Path('src/main/java/com/midonarhel/lumina/frame/Composite.java').write_text(cls)
remaining[at:at] = ['\t// the composite pass (Composite): its programs, their uniforms, and the full-screen draw', '\tpublic final Composite composite = new Composite(this);']
s = '\n'.join(remaining)
s = re.sub(r'(?<![\w.])compositeScene\(\)', 'composite.draw()', s)
s = re.sub(r'(?<![\w.])locateComposite\(', 'composite.locate(', s)
s = re.sub(r'(?<![\w.])\b(composites|compositeFull|compositeClouds|compositeWater|compositeCloudsWater|compositeEdges|compositeMark)\b', r'composite.\1', s)
s = re.sub(r'(?<![\w.])\bCompositeProgram\b', 'Composite.CompositeProgram', s)
s = s.replace('import com.midonarhel.lumina.frame.FramePacer;', 'import com.midonarhel.lumina.frame.Composite;\nimport com.midonarhel.lumina.frame.FramePacer;', 1)
sp.PLUGIN.write_text(s)
print('plugin', s.count('\n'), 'lines; Composite', cls.count('\n'), 'lines; prefixed:', len(inst), 'statics opened:', len(statics))
