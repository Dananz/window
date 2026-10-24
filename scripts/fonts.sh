#!/usr/bin/env bash
# Builds app/src/main/res/font: Syne and Golos Text, one static file per weight, each with Google
# Sans's Hebrew letters merged in, so Hebrew reads in Google Sans while Latin and numbers stay in
# the two faces. Needs fonttools (`brew install fonttools` or `pipx install fonttools`).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/main/res/font
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
GF=https://github.com/google/fonts/raw/main/ofl
curl -sL -o "$TMP/syne.ttf" "$GF/syne/Syne%5Bwght%5D.ttf"
curl -sL -o "$TMP/golos.ttf" "$GF/golostext/GolosText%5Bwght%5D.ttf"
curl -sL -o "$TMP/gs.ttf" "$GF/googlesans/GoogleSans%5BGRAD,opsz,wght%5D.ttf"
HEB="U+0590-05FF,U+FB1D-FB4F,U+20AA"
for w in 400 500 600 700; do
  fonttools varLib.instancer -q "$TMP/gs.ttf" wght=$w GRAD=0 opsz=17 -o "$TMP/gs-$w.ttf"
  pyftsubset "$TMP/gs-$w.ttf" --unicodes=$HEB --layout-features='*' --glyph-names --output-file="$TMP/gsh-$w.ttf"
done
for w in 400 500 600 700 800; do fonttools varLib.instancer -q "$TMP/syne.ttf" wght=$w -o "$TMP/syne-$w.ttf"; done
for w in 400 500 600 700 800 900; do fonttools varLib.instancer -q "$TMP/golos.ttf" wght=$w -o "$TMP/golos-$w.ttf"; done
# The merge needs the same tables on both sides: Golos's vertical metrics and Google Sans's BASE go.
# fonttools' own Python, when it came from pipx and the system one can't import it
PY=python3
$PY -c 'import fontTools' 2>/dev/null || PY="$(dirname "$(readlink -f "$(command -v fonttools)")")/python"
$PY - "$TMP" <<'PY'
import sys, glob
from fontTools.ttLib import TTFont
d = sys.argv[1]
for f in glob.glob(f"{d}/golos-*.ttf") + glob.glob(f"{d}/gsh-*.ttf"):
    t = TTFont(f)
    for tag in ("vhea", "vmtx", "BASE"):
        if tag in t: del t[tag]
    t.save(f)
PY
for fam in syne golos; do
  for f in "$TMP"/$fam-*.ttf; do
    w=$(basename "$f" .ttf | cut -d- -f2)
    gw=$(( w > 700 ? 700 : w ))
    pyftmerge --output-file="$OUT/${fam}_$w.ttf" "$f" "$TMP/gsh-$gw.ttf"
  done
done
ls -la "$OUT"
