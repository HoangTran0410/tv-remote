#!/bin/bash
# exp.sh <serial> <max-active> <beam> <lattice-beam> <wavs...> : sets the model beams on the TV (debuggable add-on), one free-speech run per wav
T=$1; MA=$2; BM=$3; LB=$4; shift 4
S=$(dirname "$0")
P=dev.tvremote.setup.voice
adb -s $T shell "run-as $P sed -i -e 's/^--max-active=.*/--max-active=$MA/' -e 's/^--beam=.*/--beam=$BM/' -e 's/^--lattice-beam=.*/--lattice-beam=$LB/' files/model/conf/model.conf"
adb -s $T shell run-as $P head -3 files/model/conf/model.conf | tr '\n\r' '  '; echo
adb -s $T shell am force-stop $P
$S/rules.sh $T '{"do":"mic_freespeech","on":true}' >/dev/null
# warm the model first (cold load is slow and not what is measured)
adb -s $T shell run-as $P cp /data/local/tmp/w.wav files/test.wav
for W in "$@"; do
  adb -s $T push $S/wav/$W.wav /data/local/tmp/w.wav >/dev/null
  adb -s $T shell run-as $P cp /data/local/tmp/w.wav files/test.wav
  adb -s $T logcat -c
  adb -s $T shell am start -n dev.tvremote.setup/.MicActivity >/dev/null
  for i in $(seq 1 60); do sleep 0.4; adb -s $T logcat -d -s MicVoice | grep -q "MicVoice: .* done" && break; done
  sleep 3
  echo "$W: $(adb -s $T logcat -d -s MicVoice TvVoice | grep -E 'free:|finish' | sed 's/.*TvVoice : //;s/.*MicVoice: //' | tr '\n' '|' | cut -c1-260)"
done
$S/rules.sh $T '{"do":"mic_freespeech","on":false}' >/dev/null
