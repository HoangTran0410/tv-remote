#!/bin/bash
D=$(dirname "$0")/wav
mkdir -p "$D"
i=1
for s in "tìm ngày xưa anh nói" "sơn tùng trên youtube" "tắt tiếng" "kênh vtv3" "hẹn giờ tắt 30 phút" "tìm bài lạc trôi" "tìm bài hát nhạc trữ tình quê hương những ngày xưa thân ái"; do
  say -v Linh -o "$D/w$i.wav" --file-format=WAVE --data-format=LEI16@16000 "$s"
  i=$((i+1))
done
for f in "$D"/w*.wav; do python3 -I -c "import wave,sys; w=wave.open(sys.argv[1]); print(sys.argv[1][-6:], round(w.getnframes()/w.getframerate(),2), w.getnchannels())" "$f"; done
