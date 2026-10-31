import re, pathlib, sys
sys.path.insert(0, 'tools'); import split_plugin as sp
lines = sp.PLUGIN.read_text().split('\n')
members = sp.plugin_members(lines)
blocks = [
  ('private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)', 'method'),
  ('private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)', 'method'),
  ('void finishInterfaceUpload()', 'method'),
  ('private void initInterfaceTexture()', 'method'),
]
region, remaining, at = sp.cut(lines, blocks)
moved = {'drawUi', 'prepareInterfaceTexture', 'finishInterfaceUpload', 'initInterfaceTexture'}
for a, b in [('\tprivate void drawUi(', '\tpublic void draw('), ('\tprivate void prepareInterfaceTexture(', '\tpublic void prepare('),
             ('void finishInterfaceUpload()', 'void finish()'), ('\tprivate void initInterfaceTexture()', '\tpublic void init()')]:
    assert region.count(a) == 1, a
    region = region.replace(a, b)
region = re.sub(r'(?<![\w.])finishInterfaceUpload\(\)', 'finish()', region)
region, inst = sp.prefix(region, members, moved, extra_locals=('overlayColor', 'canvasHeight', 'canvasWidth', 'canvasWidth', 'canvasHeight'))
statics = sp.statics_used(region, members)
remaining = sp.open_statics(remaining, statics)
imports = sp.imports_of(lines)
header = 'package com.midonarhel.lumina.frame;\n\n' + '\n'.join(i for i in imports if '.frame.' not in i) + '\nimport com.midonarhel.lumina.LuminaPlugin;\nimport com.midonarhel.lumina.LuminaPlugin.*;\nimport static com.midonarhel.lumina.LuminaPlugin.*;\n\n'
cls = header + '''/**
 * The client's interface over the scene: its texture, the banded upload (InterfaceUpload) started and finished each
 * frame, and the textured quad that draws it with the UI scaler. Split out of the plugin class verbatim; it reads the
 * plugin's state through {@code plugin}.
 */
public final class UiBlit
{
	private final LuminaPlugin plugin;

	public UiBlit(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

''' + region + '}\n'
pathlib.Path('src/main/java/com/midonarhel/lumina/frame/UiBlit.java').write_text(cls)
remaining[at:at] = ['\t// the interface over the scene (UiBlit): its texture, the banded upload, the quad that draws it', '\tpublic final UiBlit uiBlit = new UiBlit(this);']
s = '\n'.join(remaining)
s = re.sub(r'(?<![\w.])drawUi\(', 'uiBlit.draw(', s)
s = re.sub(r'(?<![\w.])prepareInterfaceTexture\(', 'uiBlit.prepare(', s)
s = re.sub(r'(?<![\w.])finishInterfaceUpload\(\)', 'uiBlit.finish()', s)
s = re.sub(r'(?<![\w.])initInterfaceTexture\(\)', 'uiBlit.init()', s)
s = s.replace('import com.midonarhel.lumina.frame.Programs;', 'import com.midonarhel.lumina.frame.Programs;\nimport com.midonarhel.lumina.frame.UiBlit;', 1)
sp.PLUGIN.write_text(s)
print('plugin', s.count('\n'), 'lines; UiBlit', cls.count('\n'), 'lines; prefixed:', len(inst), 'statics opened:', len(statics))
