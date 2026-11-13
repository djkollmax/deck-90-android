#!/bin/sh
# The release notes of a version: its NOVEDADES.md section (Spanish), then CHANGELOG.md's under "In English", then
# how to install. Usage: scripts/notes.sh 0.1.3 > notes.md
v="$1"
section() { awk -v v="$v" '
  $0 ~ "^## "v" " || $0 == "## "v { on=1; next }
  on && /^## / { exit }
  on { print }' "$1"; }
es="$(section NOVEDADES.md)"
if [ -n "$(printf '%s' "$es" | tr -d '[:space:]')" ]; then
  printf '%s\n\n---\n\n<details><summary>In English</summary>\n\n' "$es"
  section CHANGELOG.md
  printf '\n</details>\n'
else
  section CHANGELOG.md
fi
printf '\n**Instalarla o actualizarla:** con [Obtainium](https://github.com/ImranR98/Obtainium), agregando `https://github.com/djkollmax/deck-90-android` (los pasos y Android Auto, en el [README](https://github.com/djkollmax/deck-90-android#instalarla)). O bajá `deck-90-android-%s.apk` y abrilo.\n' "$v"
