#!/bin/sh
# The stereo's face is Deck 90's own (github.com/djkollmax/deck-90): this copies its pieces from a checkout of it
# (default ../deck-90) into the app's assets. Run it when the PC's stereo face changes, then commit.
set -e
D=${1:-../deck-90}
A=app/src/main/assets/face
cp "$D/src/ui/stereo/stereo-face.css" "$A/stereo-face.css"
cp "$D/src/vendor/dp940-display.js" "$A/dp940-display.js"
cp "$D/src/img/stereo/front.jpg" "$A/img/front.jpg"
echo "face synced from $D ($(cd "$D" && git describe --tags --always 2>/dev/null || echo '?'))"
