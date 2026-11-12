#!/usr/bin/env bash
# README's top animation: Google TV's home (docs/img/tv-before.webp) wipes into TV Home (tvhome-widgets-<lang>.webp), a caption
# under each. Animated WebP for the README (a GIF bands on the dark gradients, or is 4.6 MB with dithering) and an MP4 for posts
# (Reddit, X) in dev/shots/. Needs ffmpeg (libx264) + ImageMagick.
# Usage: dev/gen/hero_gif.sh [en|vi]  ->  docs/img/hero-<lang>.webp, dev/shots/hero-<lang>.mp4
set -euo pipefail
lang="${1:-en}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
img="$root/docs/img"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
font="/System/Library/Fonts/Supplemental/Arial Bold.ttf"
W=960; H=540; BAR=56                                                 # 16:9 + a caption bar
if [ "$lang" = vi ]; then
  c1="Trước: màn hình chính Google TV, đầy quảng cáo"; c2="Sau: TV Home, không quảng cáo, không cần root"
else
  c1="Before: Google TV's home screen, full of ads"; c2="After: TV Home, no ads, no root"
fi
still() {                                                            # $1 picture, $2 caption, $3 caption colour, $4 out
  magick "$1" -resize ${W}x${H}! \
    \( -size ${W}x${BAR} xc:'#0f1115' -font "$font" -pointsize 26 -fill "$3" -gravity center -annotate +0+0 "$2" \) \
    -append "$4"
}
still "$img/tv-before.webp" "$c1" '#f87171' "$tmp/a.png"
still "$img/tvhome-widgets-$lang.webp" "$c2" '#4ade80' "$tmp/b.png"
# 2.4 s before, 0.8 s wipe, 3 s after, at 25 fps
mix="[0][1]xfade=transition=wipeleft:duration=0.8:offset=2.4,fps=25"
ffmpeg -loglevel error -y -loop 1 -t 3.2 -i "$tmp/a.png" -loop 1 -t 3.8 -i "$tmp/b.png" -filter_complex "$mix" "$tmp/f%03d.png"
# WebP: one frame per still second instead of 25 identical ones (delay in 1/100 s)
frames=("$tmp"/f*.png); args=()
for i in "${!frames[@]}"; do
  if [ "$i" -eq 0 ]; then args+=(-delay 240 "${frames[0]}")
  elif [ "$i" -lt 60 ]; then continue                                # the still "before" is frame 0
  elif [ "$i" -lt 80 ]; then args+=(-delay 4 "${frames[$i]}")        # the wipe
  else args+=(-delay 300 "${frames[$i]}"); break; fi                 # the still "after"
done
magick "${args[@]}" -loop 0 -quality 82 -define webp:method=6 "$img/hero-$lang.webp"
mkdir -p "$root/dev/shots"
ffmpeg -loglevel error -y -framerate 25 -i "$tmp/f%03d.png" -c:v libx264 -pix_fmt yuv420p -crf 20 -movflags +faststart \
  "$root/dev/shots/hero-$lang.mp4"
ls -la "$img/hero-$lang.webp" "$root/dev/shots/hero-$lang.mp4"
