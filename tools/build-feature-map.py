"""Build the offline feature guide from audited Markdown and exact config declarations."""
from pathlib import Path
import html
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'


def read(path):
    raw = path.read_bytes()
    try:
        return raw.decode('utf-8')
    except UnicodeDecodeError:
        return raw.decode('cp1252')


def registry():
    source = read(ROOT / 'src/main/java/com/midonarhel/lumina/LuminaConfig.java')
    sections = {m[2]: m[1] for m in re.finditer(r'@ConfigSection\(\s*name\s*=\s*"([^"]+)".*?\)\s*String\s+(\w+)', source, re.S)}
    items = []
    for match in re.finditer(r'@ConfigItem\((.*?)\)\s*default\s+(\w+)\s+(\w+)\(\)\s*\{(.*?)\n\t\}', source, re.S):
        annotation, kind, method, body = match.groups()
        def field(key):
            value = re.search(r'\b' + key + r'\s*=\s*("(?:[^"\\]|\\.)*"|\w+)', annotation)
            return value[1].strip('"') if value else ''
        prefix = source[source.rfind('\n\t}', 0, match.start()) + 4:match.start()]
        ranges = re.findall(r'@Range\((.*?)\)', prefix, re.S)
        units = re.findall(r'@Units\((.*?)\)', prefix, re.S)
        default = re.search(r'\breturn\s+([^;]+);', body)
        items.append(dict(key=field('keyName'), name=field('name'), section=sections.get(field('section'), field('section')),
                          description=field('description'), type=kind, method=method,
                          default=default[1].strip() if default else 'See method',
                          range=ranges[-1].strip() if ranges else 'No explicit @Range',
                          units=units[-1] if units else 'No explicit @Units', hidden=field('hidden') == 'true',
                          line=source.count('\n', 0, match.start()) + 1))
    assert len(items) == len(re.findall(r'keyName\s*=', source)), 'Config item omitted'
    (DOCS / 'feature-map-settings.json').write_text(json.dumps(items, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    return items


def inline(text):
    text = html.escape(text)
    # Preserve readable references; source line suffixes are labels rather than filesystem names.
    def link(match):
        label, url = match.groups()
        url = html.unescape(url).strip('<>')
        if url.startswith(('javascript:', 'data:')):
            return label
        if url.startswith('src/'):
            url = '../' + url
        url = re.sub(r':\d+(?:-\d+)?$', '', url)
        return '<a href="' + html.escape(url, quote=True) + '">' + label + '</a>'
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', link, text)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', text)
    return text


def markdown(text):
    lines = text.splitlines()
    output = []
    paragraph = []
    fence = None
    table = False
    listing = False
    def flush():
        if paragraph:
            output.append('<p>' + inline(' '.join(paragraph)) + '</p>')
            paragraph.clear()
    for line in lines + ['']:
        if line.startswith('```'):
            flush()
            if fence is None:
                fence = []
            else:
                output.append('<pre><code>' + html.escape('\n'.join(fence)) + '</code></pre>')
                fence = None
            continue
        if fence is not None:
            fence.append(line)
            continue
        is_table = line.strip().startswith('|')
        is_list = bool(re.match(r'\s*(?:[-*]|\d+\.)\s+', line))
        if table and not is_table:
            output.append('</tbody></table></div>')
            table = False
        if listing and not is_list:
            output.append('</ul>')
            listing = False
        if not line.strip():
            flush()
        elif is_table:
            flush()
            cells = line.strip().strip('|').split('|')
            if all(re.fullmatch(r'[\s:\-]+', cell) for cell in cells):
                continue
            if not table:
                output.append('<div class="table-scroll"><table><tbody>')
                table = True
            output.append('<tr>' + ''.join('<td>' + inline(cell.strip()) + '</td>' for cell in cells) + '</tr>')
        elif line.startswith('#'):
            flush()
            level = min(5, len(line) - len(line.lstrip('#')))
            output.append(f'<h{level}>' + inline(line.lstrip('#').strip()) + f'</h{level}>')
        elif is_list:
            flush()
            if not listing:
                output.append('<ul>')
                listing = True
            output.append('<li>' + inline(re.sub(r'^\s*(?:[-*]|\d+\.)\s+', '', line)) + '</li>')
        elif line.startswith('>'):
            flush()
            output.append('<blockquote>' + inline(line.lstrip('> ').strip()) + '</blockquote>')
        elif re.fullmatch(r'\s*---+\s*', line):
            flush()
            output.append('<hr>')
        else:
            paragraph.append(line.strip())
    return '\n'.join(output)


def main():
    settings = registry()
    chapters = [('overview', 'Overview', 'feature-map.md'), ('lighting', 'Lighting & indoors', 'feature-map-lighting.md'),
                ('weather', 'Sky & weather', 'feature-map-weather.md'), ('rendering', 'Rendering & water', 'feature-map-rendering.md')]
    cards = []
    for category, title, filename in chapters:
        source = read(DOCS / filename)
        chunks = re.split(r'(?m)^## ', source)
        for index, chunk in enumerate(chunks):
            if index == 0:
                heading = title
                content = re.sub(r'^# .*\n', '', chunk)
            else:
                heading, _, content = chunk.partition('\n')
            cards.append(f'<details class="topic" data-category="{category}" id="{category}-{index}"' + (' open' if category == 'overview' and index == 0 else '') + '>'
                         + '<summary><span class="category">' + title + '</span>' + inline(heading) + '</summary>'
                         + '<div class="content">' + markdown(content) + '</div></details>')
    rows = []
    for setting in settings:
        rows.append('<tr class="setting">' + ''.join('<td>' + html.escape(str(value)) + '</td>' for value in
            [setting['section'], setting['name'] + ' (' + setting['key'] + ')', setting['default'], setting['range'], setting['units'], setting['description'], 'LuminaConfig.java:' + str(setting['line'])]) + '</tr>')
    body = '\n'.join(cards)
    page = '''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>RuneLight feature guide</title><style>
:root{color-scheme:light dark;--bg:#111820;--card:#19232e;--text:#edf2f6;--muted:#a6b6c6;--line:#334353;--accent:#8ed8c7}
@media(prefers-color-scheme:light){:root{--bg:#f3f6f7;--card:#fff;--text:#172c3b;--muted:#516775;--line:#ccd7dd;--accent:#116c61}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.6 system-ui,sans-serif}header,main{max-width:1200px;margin:auto;padding:24px}h1{font-size:32px;margin:0}h2{font-size:23px}h3{font-size:19px}a{color:var(--accent)}.meta,.category{color:var(--muted);font-size:13px}.toolbar{position:sticky;top:0;background:var(--bg);z-index:2;border-block:1px solid var(--line);padding:16px 0}input{width:100%;padding:12px;font:inherit;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--text)}nav{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}button{font:inherit;font-size:14px;padding:7px 12px;border-radius:6px;background:var(--card);border:1px solid var(--line);color:var(--text);cursor:pointer}button[aria-pressed=true]{border-color:var(--accent);color:var(--accent)}.actions{display:flex;gap:8px;align-items:center;margin:16px 0}.actions span{margin-left:auto;color:var(--muted);font-size:13px}.topic{background:var(--card);border:1px solid var(--line);border-radius:10px;margin:12px 0}.topic summary{cursor:pointer;padding:16px 20px;font-size:18px;font-weight:600}.category{display:inline-block;margin-right:14px;font-weight:400}.content{padding:0 24px 20px;overflow-wrap:anywhere}.content>h1{font-size:23px}.content p{max-width:100ch}pre{padding:14px;overflow:auto;background:var(--bg);border-radius:6px;font-size:13px}code{font-size:.9em;color:var(--accent)}blockquote{border-left:3px solid var(--accent);padding-left:15px;margin-left:0}.table-scroll{overflow:auto}table{border-collapse:collapse;width:100%;font-size:14px}td,th{text-align:left;padding:10px;border:1px solid var(--line);vertical-align:top}tr:first-child{font-weight:600}#settings{background:var(--card);padding:20px;border:1px solid var(--line);border-radius:10px}.hidden{display:none!important}footer{color:var(--muted);font-size:13px;margin:24px 0}@media(max-width:650px){header,main{padding:16px}.category{display:block}.content{padding:0 14px 14px}h1{font-size:26px}}
</style></head><body><header><p class="meta">CURRENT WORKING TREE · SOURCE AUDIT · 7 NOVEMBER 2026</p><h1>RuneLight feature guide</h1><p>Behavior, settings, interactions, and open questions. Code evidence is separate from visual acceptance and real-game performance.</p></header><main>
<div class="toolbar"><label for="search">Find a behavior or parameter</label><input id="search" type="search" placeholder="Try: indoors, roof, night-only, fog density, render scale"><nav aria-label="Feature categories">
<button data-category="all" aria-pressed="true">All features</button><button data-category="overview">Start here</button><button data-category="lighting">Lighting & indoors</button><button data-category="weather">Sky & weather</button><button data-category="rendering">Rendering & water</button><button data-category="settings">59 settings</button></nav></div>
<div class="actions"><button id="expand">Expand matches</button><button id="collapse">Collapse all</button><span id="matches" role="status" aria-live="polite"></span></div>
''' + body + '''<section id="settings"><h2>Declared settings, not live saved values</h2><p>Exact declaration expressions from LuminaConfig. Tooltips may be misleading; consult the behavior chapters. An absent annotation does not imply unlimited runtime behavior.</p><div class="table-scroll"><table><thead><tr><th>Section</th><th>Setting / key</th><th>Default expression</th><th>Declared range</th><th>Unit annotation</th><th>Tooltip (not authoritative)</th><th>Source</th></tr></thead><tbody>''' + ''.join(rows) + '''</tbody></table></div></section>
<footer>Offline guide. No settings are changed by this page. Source documents: <a href="feature-map.md">overview</a> · <a href="feature-map-lighting.md">lighting</a> · <a href="feature-map-weather.md">weather</a> · <a href="feature-map-rendering.md">rendering</a> · <a href="point-light-occlusion-performance.md">measurement limits</a></footer>
</main><script>
const search=document.getElementById('search'),topics=[...document.querySelectorAll('.topic')],rows=[...document.querySelectorAll('.setting')],buttons=[...document.querySelectorAll('nav button')];let category='all';
function update(){const query=search.value.trim().toLowerCase();let visible=0,settings=0;
for(const topic of topics){const match=(category==='all'||category===topic.dataset.category)&&topic.textContent.toLowerCase().includes(query);topic.classList.toggle('hidden',!match);if(match){visible++;if(query)topic.open=true;}}
for(const row of rows){const match=(category==='all'||category==='settings')&&row.textContent.toLowerCase().includes(query);row.classList.toggle('hidden',!match);if(match)settings++;}
document.getElementById('settings').classList.toggle('hidden',settings===0);document.getElementById('matches').textContent=visible+' sections · '+settings+' settings';for(const button of buttons)button.setAttribute('aria-pressed',String(button.dataset.category===category));}
search.addEventListener('input',update);for(const button of buttons)button.addEventListener('click',()=>{category=button.dataset.category;update();});
document.getElementById('expand').addEventListener('click',()=>{for(const topic of topics)if(!topic.classList.contains('hidden'))topic.open=true;});document.getElementById('collapse').addEventListener('click',()=>{for(const topic of topics)topic.open=false;});update();
</script></body></html>'''
    (DOCS / 'feature-map.html').write_text(page, encoding='utf-8')
    print(f'Built {len(cards)} sections and {len(settings)} settings ({len(page):,} characters)')


if __name__ == '__main__':
    main()
