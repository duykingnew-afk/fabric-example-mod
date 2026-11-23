#!/usr/bin/env bash
# Dumps what the Iris and Sodium jars contain around render target handling (class list, signatures and
# bytecode), so Pocket FSR can be made compatible by reading the real code instead of guessing.
# Input: inspect/jars/{iris,sodium}-*.jar   Output: inspect/out/*  (packed by the workflow)
set -u
JARS=inspect/jars
OUT=inspect/out
X=inspect/x
rm -rf "$OUT" "$X"
mkdir -p "$OUT" "$X"

IRIS=$(ls "$JARS"/iris-*.jar | head -n 1)
SODIUM=$(ls "$JARS"/sodium-*.jar | head -n 1)
echo "iris jar: $IRIS"
echo "sodium jar: $SODIUM"
unzip -q -o "$IRIS" -d "$X/iris"
unzip -q -o "$SODIUM" -d "$X/sodium"

cp "$X/iris/fabric.mod.json" "$OUT/iris-fabric.mod.json" 2>/dev/null || true
cp "$X/sodium/fabric.mod.json" "$OUT/sodium-fabric.mod.json" 2>/dev/null || true
unzip -Z1 "$IRIS" > "$OUT/iris-files.txt"
(cd "$X/iris" && find . -name '*.class' | sed 's|^\./||; s|\.class$||' | sort) > "$OUT/iris-classes.txt"
wc -l "$OUT/iris-classes.txt"

# which classes mention the render target version counters (callers and implementers)
grep -rl "DepthBufferVersion\|ColorBufferVersion\|depthBufferVersion\|colorBufferVersion" "$X/iris" > "$OUT/iris-version-users.txt" 2>/dev/null || true
cat "$OUT/iris-version-users.txt"

# signatures of everything in the targets package
while read -r c; do
  javap -p -cp "$X/iris" "$(echo "$c" | tr / .)" 2>&1
done < <(grep -E '/targets/' "$OUT/iris-classes.txt") > "$OUT/iris-targets-signatures.txt"

# bytecode of the classes that matter
PATTERN='RenderTarget|FinalPassRenderer|IrisRenderingPipeline|PipelineManager|MixinGameRenderer|MixinLevelRenderer|MixinMinecraft|MixinWindow|DepthTexture|DepthCopy'
while read -r c; do
  name=$(echo "$c" | tr / _ | tr '$' '-')
  javap -p -c -constants -cp "$X/iris" "$(echo "$c" | tr / .)" > "$OUT/code-$name.txt" 2>&1
done < <(grep -E "$PATTERN" "$OUT/iris-classes.txt")
ls -la "$OUT" | head -80

# the same for the (few) places where Sodium touches the main render target
grep -rl "getMainRenderTarget\|method_1522" "$X/sodium" 2>/dev/null | sed "s|$X/sodium/||" > "$OUT/sodium-main-target-users.txt" || true
cat "$OUT/sodium-main-target-users.txt"

tar czf inspect/iris-dump.tar.gz -C inspect out
ls -la inspect/iris-dump.tar.gz
