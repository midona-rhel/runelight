import re, pathlib, sys
sys.path.insert(0, 'tools'); import split_plugin as sp
lines = sp.PLUGIN.read_text().split('\n')
members = sp.plugin_members(lines)
blocks = [
  ('private void runCommand(String[] args)', 'method'),
  ('private Image screenshot()', 'method'),
]
region, remaining, at = sp.cut(lines, blocks)
moved = {'runCommand', 'screenshot'}
assert region.count('\tprivate void runCommand(String[] args)') == 1
region = region.replace('\tprivate void runCommand(String[] args)', '\tpublic void run(String[] args)')
region = region.replace('\tprivate Image screenshot()', '\tpublic Image screenshot()')
region, inst = sp.prefix(region, members, moved, extra_locals=('args', 'image', 'dim', 't', 'header', 'k0', 'target'))
statics = sp.statics_used(region, members)
remaining = sp.open_statics(remaining, statics)
imports = sp.imports_of(lines)
header = 'package com.midonarhel.lumina.dev;\n\n' + '\n'.join(i for i in imports if '.dev.' not in i) + '\nimport com.midonarhel.lumina.LuminaPlugin;\nimport com.midonarhel.lumina.LuminaPlugin.*;\nimport static com.midonarhel.lumina.LuminaPlugin.*;\n\n'
cls = header + '''/**
 * The developer commands (::runelight cam|capture|set|snapshot|pace|swapcapture and the command file) and the
 * screenshot they capture. Split out of the plugin class verbatim; they drive the plugin through {@code plugin}.
 */
public final class Commands
{
	private final LuminaPlugin plugin;

	public Commands(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

''' + region + '}\n'
pathlib.Path('src/main/java/com/midonarhel/lumina/dev/Commands.java').write_text(cls)
remaining[at:at] = ['\t// the developer commands (Commands): ::runelight …, the command file, the screenshot', '\tpublic final Commands commands = new Commands(this);']
s = '\n'.join(remaining)
s = re.sub(r'(?<![\w.])runCommand\(', 'commands.run(', s)
s = s.replace('this::commands.run', 'commands::run').replace('this::runCommand', 'commands::run')
s = re.sub(r'(?<![\w.])screenshot\(\)', 'commands.screenshot()', s)
s = s.replace('import com.midonarhel.lumina.dev.CommandFile;', 'import com.midonarhel.lumina.dev.CommandFile;\nimport com.midonarhel.lumina.dev.Commands;', 1)
sp.PLUGIN.write_text(s)
print('plugin', s.count('\n'), 'lines; Commands', cls.count('\n'), 'lines; prefixed:', len(inst), 'statics opened:', len(statics))
