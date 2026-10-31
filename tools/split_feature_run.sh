#!/bin/zsh
# tools/split_feature_run.sh "<commit message>" <split_feature.py args...>
set -u
msg=$1; shift
python3 -I tools/split_feature.py "$@" || exit 1
for i in $(seq 1 10); do
  out=$(./gradlew -q compileJava compileTestJava 2>&1); n=$(echo "$out" | grep -c "error:")
  echo "pass $i: $n errors"; [ "$n" = 0 ] && break
  echo "$out" | python3 -I tools/open_visibility.py | cut -c1-300
done
if [ "$n" != 0 ]; then echo "$out" | grep -A3 "error:" | grep -E "error:|symbol:" | sed 's|.*/com/midonarhel/lumina/||' | sort | uniq -c | sort -rn | head -14; exit 2; fi
./gradlew -q test 2>&1 | grep -v "^$" | head -3
python3 -I - <<'PY' || exit 3
import xml.etree.ElementTree as ET,glob,sys
t=f=0
for x in glob.glob('build/test-results/test/TEST-*.xml'):
    r=ET.parse(x).getroot(); t+=int(r.get('tests')); f+=int(r.get('failures'))+int(r.get('errors'))
    for tc in r.iter('testcase'):
        fl=tc.find('failure') if tc.find('failure') is not None else tc.find('error')
        if fl is not None: print("FAIL", r.get('name').split('.')[-1], tc.get('name'), (fl.get('message') or '')[:100])
print(f"tests {t} failures {f}"); sys.exit(1 if f else 0)
PY
git add -A src tools && git commit -q -m "$msg" && git log --oneline -1 | cut -c1-60
