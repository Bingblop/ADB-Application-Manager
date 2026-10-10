#!/usr/bin/env python3
"""Builds assets/hsinfo.js: what every Android setting does and what its values mean (the Hidden Settings tab shows it).

  python3 tools/hsinfo/build.py                     # curated/*.txt over the text taken from Android's own documentation -> assets/hsinfo.js
  python3 tools/hsinfo/build.py extract Settings.java   # refresh tools/hsinfo/aosp-settings-docs.json from Android's Settings.java:
      curl -o Settings.java https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/provider/Settings.java

Two layers, the second wins:
  1. every setting that Android's Settings.java names (Global, Secure, System), with the first sentences of its documentation and the values that
     documentation states; short and rough, but it covers all of them. (AOSP, Apache License 2.0, https://source.android.com/)
  2. curated/<table>.txt: the settings people meet, written for someone who has never read the Android source.
     One line each:   key | what it does | values | risk      (risk is optional: conn, lock or break, see SDB_RISKS in assets/index.html)
     values: free text; "N = meaning" parts separated by ; are read by the app (it shows what the current value means and offers them as buttons).
Output format of hsinfo.js: window.HS_INFO = { global: "key|what|values|risk\\n...", secure: ..., system: ... } (the app parses it on first use).
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DOCS = os.path.join(HERE, 'aosp-settings-docs.json')
OUT = os.path.join(ROOT, 'assets', 'hsinfo.js')
TABLES = ('global', 'secure', 'system')
SKIP = {'bluetooth', 'cell', 'wifi', 'nfc', 'wimax', 'uwb', 'sms', 'sip_always', 'sip_address_only', 'sip_ask_me_each_time', 'show_gtalk_service_status',
        'ringtone_cache', 'notification_sound_cache', 'alarm_alert_cache', '_last_audible'}
BOOL_START = re.compile(r'(Whether|Indicates whether|Controls whether|Determines whether|Specifies whether|Setting that specifies whether|If true|Setting to enable|Enable )')


def extract(path):
    s = open(path, encoding='utf-8').read()
    ranges = {}
    for name, key in (('System', 'system'), ('Secure', 'secure'), ('Global', 'global')):
        m = re.search(r'public static final class %s extends NameValueTable' % name, s)
        ranges[key] = m.start()
    order = sorted(ranges.items(), key=lambda kv: kv[1])
    end = re.search(r'public static final class Wearable\b', s)
    bounds = {}
    for i, (k, a) in enumerate(order):
        b = order[i + 1][1] if i + 1 < len(order) else (end.start() if end else len(s))
        bounds[k] = (a, b)
    out = {}
    for ns, (a, b) in bounds.items():
        items = []
        for m in re.finditer(r'((?:/\*\*(?:(?!\*/).)*\*/\s*)?)((?:@\w+(?:\([^)]*\))?\s*)*)public static final String (\w+)\s*=\s*"([^"]+)";', s[a:b], re.S):
            doc, ann, const, key = m.groups()
            if key.startswith('android.') or (' ' in key and '.' in key):
                continue
            d = re.sub(r'^\s*/\*\*|\*/\s*$', '', doc)
            d = re.sub(r'^\s*\*\s?', '', d, flags=re.M)
            d = re.sub(r'\{@\w+\s+([^}]*)\}', lambda x: x.group(1).split('#')[-1].split('.')[-1] if x.group(1) else '', d)
            d = re.sub(r'<[^>]+>', '', d)
            d = re.sub(r'\s+', ' ', d).strip()
            items.append({'key': key, 'doc': d[:900], 'dep': '@Deprecated' in ann})
        out[ns] = items
    json.dump(out, open(DOCS, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    print('wrote', DOCS, {k: len(v) for k, v in out.items()})


def clean(doc):
    t = doc
    dep = bool(re.search(r'@deprecated|@removed', t))
    t = re.sub(r'\{@hide\}', '', t)
    t = re.sub(r'@(hide|deprecated|removed|see|link|SystemApi|TestApi|Readable|UnsupportedAppUsage|code|since|param|return)\b.*$', '', t).strip()
    t = re.sub(r'\bSee (also )?[A-Z]\w+\.?$', '', t).strip()
    return re.sub(r'\s+', ' ', t).strip(), dep


def first_sentences(t, limit=200):
    parts = re.split(r'(?<=[.!?])\s+(?=[A-Z0-9"(])', t)
    out = ''
    for p in parts:
        if not out:
            out = p
        elif len(out) + 1 + len(p) <= limit:
            out += ' ' + p
        else:
            break
    if len(out) > limit + 40:
        out = out[:limit].rsplit(' ', 1)[0] + '...'
    return out


def values_of(t):
    low = t.lower()
    pairs = re.findall(r'(?<![\w.])(-?\d+)\s*(?:=|==|--|-|:)\s+([A-Za-z][^0-9]{0,60}?)(?=\s+-?\d+\s*(?:=|==|--|-|:)\s|[.;]|$)', t)
    if len(pairs) >= 2:
        return '; '.join('%s = %s' % (a, b.strip(' ,;)(')) for a, b in pairs[:8])
    if re.search(r'boolean|\(1 or 0\)|1 for true and 0 for false|0 or 1|0 for false, 1 for true|"0" = false, "1" = true|\(0 = false, 1 = true\)|0 = false, 1 = true', t, re.I):
        return '0 = off; 1 = on'
    if re.search(r'key=value list|key=value pairs|name=value', t):
        return 'text: name=value pairs separated by commas'
    if re.search(r'comma[- ]separated|separated by commas|colon[- ]separated|semi-colon separated', low):
        return 'a list, items separated by , or :'
    if re.match(r'(URL|URI|The URL|The URI)', t):
        return 'web address (URL)'
    m = re.search(r'\bin (milliseconds|seconds|minutes|hours|days)\b', low)
    if m:
        return m.group(1)
    if re.match(r'(The )?package name', t, re.I):
        return 'package name'
    if re.search(r'type: ?string', low):
        return 'text'
    if re.search(r'float', low):
        return 'decimal number'
    if re.search(r'type: ?(int|long)\b', low):
        return 'whole number'
    if BOOL_START.match(t):
        return '0 = off; 1 = on'
    return ''


def safe(t):
    return t.replace('|', '/').replace('\\', '/').replace('`', "'").replace('${', '$ {')


def baseline():
    docs = json.load(open(DOCS, encoding='utf-8'))
    out = {}
    for ns in TABLES:
        rows = {}
        for it in docs[ns]:
            key = it['key']
            if not re.fullmatch(r'[a-z0-9_.:\-]+', key) or key.startswith('_') or key in SKIP:
                continue
            t, dep = clean(it['doc'])
            if not t:
                continue
            desc = first_sentences(re.sub(r'\s*(Type|Default)\s*:\s.*$', '', t, flags=re.I))
            if dep or it.get('dep'):
                desc = 'Deprecated. ' + desc
            rows[key] = [safe(desc), safe(values_of(t)), '']
        out[ns] = rows
    return out


def curated(ns):
    rows = {}
    p = os.path.join(HERE, 'curated', ns + '.txt')
    for n, line in enumerate(open(p, encoding='utf-8').read().split('\n'), 1):
        if not line.strip() or line.startswith('#'):
            continue
        f = [x.strip() for x in line.split(' | ')]
        if len(f) < 3 or len(f) > 4 or not re.fullmatch(r'[a-z0-9_.:\-]+', f[0]):
            sys.exit('%s:%d: expected "key | what | values | risk(optional)": %s' % (p, n, line[:80]))
        if len(f) == 4 and f[3] not in ('conn', 'lock', 'break'):
            sys.exit('%s:%d: risk must be conn, lock or break: %s' % (p, n, f[3]))
        if f[0] in rows:
            sys.exit('%s:%d: %s is there twice' % (p, n, f[0]))
        rows[f[0]] = [f[1], f[2], f[3] if len(f) == 4 else '']
    return rows


def build():
    base = baseline()
    parts = {}
    total = {}
    for ns in TABLES:
        rows = dict(base[ns])
        cur = curated(ns)
        rows.update(cur)
        lines = []
        for k in sorted(rows):
            d, v, r = rows[k]
            for field in (d, v, r):
                if '|' in field or '\n' in field or '`' in field or '\\' in field or '${' in field:
                    sys.exit('%s %s: a field holds | ` \\ ${ or a line break: %s' % (ns, k, field[:80]))
            lines.append('|'.join((k, d, v, r)).rstrip('|'))
        parts[ns] = '\n'.join(lines)
        total[ns] = (len(rows), len(cur))
    js = ('// What every Android setting does and what its values mean. Made by tools/hsinfo/build.py: do not edit by hand.\n'
          '// Text of the settings Android documents comes from AOSP (Settings.java, Apache License 2.0); the rest was written for this app.\n'
          'window.HS_INFO = {\n' + ',\n'.join('%s: `%s`' % (ns, parts[ns]) for ns in TABLES) + '\n};\n'
          'if (window.onHsInfoLoaded) window.onHsInfoLoaded();\n')
    open(OUT, 'w', encoding='utf-8').write(js)
    print('wrote', OUT, len(js), 'bytes;', ', '.join('%s %d (%d written by hand)' % (ns, *total[ns]) for ns in TABLES))


if __name__ == '__main__':
    if len(sys.argv) >= 3 and sys.argv[1] == 'extract':
        extract(sys.argv[2])
    else:
        build()
