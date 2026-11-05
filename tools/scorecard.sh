#!/bin/zsh
# The occlusion scorecard of one build (occlusion/SCORECARD.md).
#   scorecard.sh <branch or commit> [name=<card name>] [dumps=a,b] [parts=leak,fog,particles,motion,memory] [rounds=N] [vs=<card>]
# Makes a throwaway worktree of the build, lays the card from occ/scorecard over it (nothing committed, the build's own
# code untouched), takes the oracle if it isn't cached, scores every dump, times it and writes
# occlusion/cards/<name>/card.md. With vs=baseline the cost rounds interleave with occ/scorecard's own tree, and the
# frames get a black and white diff against the baseline's.
# Every GPU step holds the machine's slot (gpu-lock.sh), each dump apart, and lets go between them.
set -e
REF=$1; shift
OCC=/Users/midona/Code/lumen/occlusion
REPO=~/Code/runelight
CARDTREE=~/Code/runelight-occ-scorecard
ALL=fps1,artifact1,glow1,nightref,snow1,water2,fall4,tree1,snowtree1
NAME=${REF//\//-}; DUMPS=$ALL; PARTS=leak,fog,particles,motion,memory; ROUNDS=3; VS=
for a in "$@"; do
  case $a in
    name=*) NAME=${a#name=};;
    dumps=*) DUMPS=${a#dumps=};;
    parts=*) PARTS=${a#parts=};;
    rounds=*) ROUNDS=${a#rounds=};;
    vs=*) VS=${a#vs=};;
    *) echo "unknown $a"; exit 2;;
  esac
done
OUT=$OCC/cards/$NAME
CACHE=$OCC/.oracle
TREE=~/Code/runelight-occ-card-$NAME
mkdir -p $OUT
quiet() { grep -v ' DEBUG \| INFO \|^light map\|LWJGL\|UNSUPPORTED\|VAO' || true; }

# the build, with the card laid over it
if [[ -d $TREE ]]; then git -C $REPO worktree remove --force $TREE; fi
git -C $REPO worktree add --detach $TREE $REF > /dev/null 2>&1
git -C $TREE checkout occ/scorecard -- gradle/scorecard.gradle \
  'src/test/java/com/midonarhel/lumina/harness/Card*.java' \
  src/test/java/com/midonarhel/lumina/harness/Scorecard.java \
  src/test/java/com/midonarhel/lumina/light/CardLamps.java \
  src/test/java/com/midonarhel/lumina/scene/CardZones.java
grep -q scorecard.gradle $TREE/build.gradle || echo "\napply from: 'gradle/scorecard.gradle'" >> $TREE/build.gradle
( cd $TREE && ./gradlew --offline -q testClasses 2>&1 | grep -v '^Note' || true )
{ echo "Build \`$REF\` ($(git -C $REPO rev-parse --short $REF)), card \`$(git -C $REPO rev-parse --short occ/scorecard)\`, taken $(date '+%Y-%m-%d %H:%M')."; } > $OUT/build.txt

for d in ${(s:,:)DUMPS}; do
  # the oracle: what every method is measured against, from the card's own tree, once
  if [[ ! -f $CACHE/$d/oracle.bin ]]; then
    ( cd $CARDTREE && $OCC/gpu-lock.sh ./gradlew --offline -q scorecard -Pargs="phase=probe dump=$d cache=$CACHE" 2>&1 | quiet
      ./gradlew --offline -q scorecard -Pargs="phase=oracle dump=$d cache=$CACHE" 2>&1 | quiet )
  fi
  [[ $PARTS == none ]] && continue
  echo "== $d: render"
  ( cd $TREE && $OCC/gpu-lock.sh ./gradlew --offline -q scorecard -Pargs="phase=render dump=$d cache=$CACHE out=$OUT parts=$PARTS" 2>&1 | quiet )
done

# the cost: a round is one JVM per dump; with vs= the baseline's tree takes a turn between each of this build's
if (( ROUNDS > 0 )); then
  [[ -n $VS ]] && mkdir -p $OUT/vs-$VS
  for (( r = 0; r < ROUNDS; ++r )); do
    for d in ${(s:,:)DUMPS}; do
      echo "== $d: cost round $r"
      ( cd $TREE && $OCC/gpu-lock.sh ./gradlew --offline -q scorecard -Pargs="phase=cost dump=$d out=$OUT round=$r" 2>&1 | quiet )
      if [[ -n $VS ]]; then
        ( cd $CARDTREE && $OCC/gpu-lock.sh ./gradlew --offline -q scorecard -Pargs="phase=cost dump=$d out=$OUT/vs-$VS round=$r" 2>&1 | quiet )
      fi
    done
  done
fi

BASE=$OCC/cards/${VS:-baseline}
( cd $TREE && ./gradlew --offline -q scorecard -Pargs="phase=report dump=$(echo $DUMPS | cut -d, -f1) out=$OUT baseline=$BASE dumps=$DUMPS" 2>&1 | quiet )
if [[ -n $VS && -d $BASE/frames ]]; then
  ( cd $TREE && ./gradlew --offline -q imageDiff -Pargs="$BASE/frames $OUT/frames $OUT/diff 2" 2>&1 | quiet )
fi
git -C $REPO worktree remove --force $TREE
echo "card: $OUT/card.md"
