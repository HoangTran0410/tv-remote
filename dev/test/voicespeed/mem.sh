#!/bin/bash
# mem.sh <serial> [searches] : PSS (KB) of the voice add-on before the first use, after N mixed uses (free speech on, kept decoder) and 5 min idle
# after the last (the model is freed after 5 min). Needs the -Pvoicetest add-on and wav/ (mkwav.sh).
T=$1; N=${2:-20}
S=$(cd "$(dirname "$0")" && pwd)
pss() { adb -s $T shell dumpsys meminfo dev.tvremote.setup.voice | grep "TOTAL PSS" | head -1 | awk '{print $3}'; }
$S/rules.sh $T '{"do":"mic_freespeech","on":true}' >/dev/null
adb -s $T shell am force-stop dev.tvremote.setup.voice
sleep 2
echo "before: not running ($(adb -s $T shell pidof dev.tvremote.setup.voice | tr -d '\r'))"
for i in $(seq 1 $N); do
  W=$(( (i % 7) + 1 ))
  adb -s $T push $S/wav/w$W.wav /data/local/tmp/w.wav >/dev/null
  adb -s $T shell run-as dev.tvremote.setup.voice cp /data/local/tmp/w.wav files/test.wav
  adb -s $T logcat -c
  adb -s $T shell am start -n dev.tvremote.setup/.MicActivity >/dev/null
  for k in $(seq 1 90); do sleep 0.4; adb -s $T logcat -d -s MicVoice | grep -q "MicVoice: .* done" && break; done
  sleep 1
  if [ $i = 1 ]; then echo "after 1st: $(pss) KB"; fi
done
echo "after $N: $(pss) KB"
sleep 330
echo "idle 5.5 min: $(pss) KB (pid $(adb -s $T shell pidof dev.tvremote.setup.voice | tr -d '\r'))"
adb -s $T logcat -d -s TvVoice | grep -i "freed\|release\|close" | tail -3
$S/rules.sh $T '{"do":"mic_freespeech","on":false}'
