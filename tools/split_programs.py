import re, pathlib, sys
sys.path.insert(0, 'tools'); import split_plugin as sp
lines = sp.PLUGIN.read_text().split('\n')
members = sp.plugin_members(lines)
blocks = [
  ('private Template createTemplate()', 'method'),
  ('private int compileComposite(int pass)', 'method'),
  ('private void initProgram()', 'method'),
  ('private void initUniforms()', 'method'),
  ('private void shutdownProgram()', 'method'),
  ('private boolean reloadShaders()', 'method'),
  ('private static String firstLines(String s, int n)', 'method'),
]
region, remaining, at = sp.cut(lines, blocks)
moved = {'createTemplate', 'compileComposite', 'initProgram', 'initUniforms', 'shutdownProgram', 'reloadShaders', 'firstLines'}
for a, b in [('\tprivate void initProgram()', '\tpublic void initProgram()'), ('\tprivate void initUniforms()', '\tpublic void initUniforms()'),
             ('\tprivate void shutdownProgram()', '\tpublic void shutdownProgram()'), ('\tprivate boolean reloadShaders()', '\tpublic boolean reloadShaders()')]:
    assert region.count(a) == 1, a
    region = region.replace(a, b)
region, inst = sp.prefix(region, members, moved, extra_locals=('template', 'program', 'programs', 'c', 'ex', 'msg', 'again', 'chat'))
statics = sp.statics_used(region, members)
remaining = sp.open_statics(remaining, statics)
imports = sp.imports_of(lines)
header = 'package com.midonarhel.lumina.frame;\n\n' + '\n'.join(i for i in imports if '.frame.' not in i) + '\nimport com.midonarhel.lumina.LuminaPlugin;\nimport com.midonarhel.lumina.LuminaPlugin.*;\nimport static com.midonarhel.lumina.LuminaPlugin.*;\n\n'
cls = header + '''/**
 * The shader programs: the template with the build's defines and includes, compiling every program (scene, UI,
 * composite passes, AO, denoise, shadow, fog, sky, weather, water, upscale), locating their uniforms into the plugin's
 * fields, hot reload, and freeing them. Split out of the plugin class verbatim; it writes the plugin's program and
 * uniform fields through {@code plugin}.
 */
public final class Programs
{
	private final LuminaPlugin plugin;

	public Programs(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

''' + region + '}\n'
pathlib.Path('src/main/java/com/midonarhel/lumina/frame/Programs.java').write_text(cls)
remaining[at:at] = ['\t// the shader programs (Programs): template, compile, uniform locations, hot reload, free', '\tpublic final Programs programs = new Programs(this);']
s = '\n'.join(remaining)
for name in ['initProgram', 'initUniforms', 'shutdownProgram', 'reloadShaders']:
    s = re.sub(r'(?<![\w.])' + name + r'\(\)', 'programs.' + name + '()', s)
    s = s.replace('this::programs.' + name, 'programs::' + name)
    s = s.replace('this::' + name, 'programs::' + name)
s = s.replace('import com.midonarhel.lumina.frame.FramePacer;', 'import com.midonarhel.lumina.frame.FramePacer;\nimport com.midonarhel.lumina.frame.Programs;', 1)
sp.PLUGIN.write_text(s)
print('plugin', s.count('\n'), 'lines; Programs', cls.count('\n'), 'lines; prefixed:', len(inst), 'statics opened:', len(statics))
