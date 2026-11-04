#!/bin/bash
# batch.sh <serial> <tag> <runs> : both modes x 7 wavs x runs; one TSV line per run on stdout (summary: python3 -I sum.py <tsv>)
# Speed of TV Setup Voice on a real TV. Needs: bash mkwav.sh once (macOS `say`), the voice add-on built with -Pvoicetest
# (a debuggable release build that plays files/test.wav and needs no microphone), the TV awake. exp.sh: free-speech runs with chosen beams.
T=$1; TAG=$2; RUNS=${3:-3}
S=$(cd "$(dirname "$0")" && pwd); mkdir -p "$S/logs"
RS=$S/rules.sh
DUR=(0 1.42 1.63 0.62 1.44 1.68 1.23 5.43)
for MODE in cmd free; do
  if [ $MODE = free ]; then ON=true; else ON=false; fi
  $RS $T "{\"do\":\"mic_freespeech\",\"on\":$ON}" >/dev/null
  sleep 1
  for W in 1 2 3 4 5 6 7; do
    adb -s $T push $S/wav/w$W.wav /data/local/tmp/w.wav >/dev/null
    adb -s $T shell run-as dev.tvremote.setup.voice cp /data/local/tmp/w.wav files/test.wav
    for R in $(seq 1 $RUNS); do
      adb -s $T logcat -c
      PID=$(adb -s $T shell pidof dev.tvremote.setup.voice | tr -d '\r')
      C0=""; [ -n "$PID" ] && C0=$(adb -s $T shell "cat /proc/$PID/stat" | tr -d '\r' | awk '{print $14+$15}')
      adb -s $T shell am start -n dev.tvremote.setup/.MicActivity >/dev/null
      for i in $(seq 1 90); do
        sleep 0.4
        adb -s $T logcat -d -v epoch -s MicVoice | grep -q "MicVoice: .* done" && break
      done
      sleep 2
      PID=$(adb -s $T shell pidof dev.tvremote.setup.voice | tr -d '\r')
      C1=""; [ -n "$PID" ] && C1=$(adb -s $T shell "cat /proc/$PID/stat" | tr -d '\r' | awk '{print $14+$15}')
      L=$(adb -s $T logcat -d -v epoch -s MicVoice TvVoice | tr -d '\r')
      echo "$L" > $S/logs/$TAG-$MODE-w$W-r$R.log
      END=$(echo "$L" | grep "test audio ended" | head -1 | awk '{print $1}')
      RES=$(echo "$L" | grep "MicVoice: .*results" | head -1 | awk '{print $1}')
      PICK=$(echo "$L" | grep "MicVoice: .*finish" | head -1 | sed 's/.*finish //')
      RAW=$(echo "$L" | grep "MicVoice: .*results" | head -1 | sed 's/.*results //')
      FREE=$(echo "$L" | grep "TvVoice : free:" | head -1 | sed 's/.*free: //')
      MS=$(python3 -I -c "import sys; e,r=sys.argv[1:3]; print(round((float(r)-float(e))*1000) if e and r else 'NA')" "$END" "$RES")
      echo -e "$TAG\t$MODE\tw$W\t$R\t${MS}\t$PICK\t$RAW\t$FREE\tcpu_jiffies=$C0>$C1"
    done
  done
done
$RS $T '{"do":"mic_freespeech","on":false}'
