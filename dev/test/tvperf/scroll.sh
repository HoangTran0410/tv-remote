#!/bin/bash
# scroll.sh <serial> <label> [gap=0.3]: TV Home's Customize → Widgets list (a TvScroll) walked down 12 rows and back up, one key every
# [gap] s: frames, jank, p90 / p99, CPU ms of the app, per walk. The glide draws ~20 frames a move where Android's jump drew 2,
# so read CPU ms per frame and p90 against the old build, not the frame count. Use the RELEASE build (../AGENTS.md).
D=$1; L=$2; G=${3:-0.3}
A="adb -s $D shell"
P=dev.tvremote.home
tck=$($A getconf CLK_TCK 2>/dev/null | tr -d '\r'); [ -z "$tck" ] && tck=100
$A input keyevent HOME; sleep 3
$A am start -a dev.tvremote.home.CUSTOMIZE -n $P/.HomeActivity --es at widgets >/dev/null 2>&1; sleep 3
pid=$($A pidof $P | tr -d '\r')
$A dumpsys gfxinfo $P reset >/dev/null
c0=$($A cat /proc/$pid/stat | awk '{print $14+$15}')
for i in $(seq 12); do $A input keyevent DPAD_DOWN; sleep "$G"; done
for i in $(seq 12); do $A input keyevent DPAD_UP; sleep "$G"; done
sleep 1
c1=$($A cat /proc/$pid/stat | awk '{print $14+$15}')
g=$($A dumpsys gfxinfo $P)
tot=$(echo "$g" | grep -m1 "Total frames rendered" | awk -F: '{print $2}' | tr -d ' \r')
jank=$(echo "$g" | grep -m1 "Janky frames:" | awk -F: '{print $2}' | tr -d '\r' | xargs)
p90=$(echo "$g" | grep -m1 "90th percentile" | awk -F: '{print $2}' | tr -d ' \r')
p99=$(echo "$g" | grep -m1 "99th percentile" | awk -F: '{print $2}' | tr -d ' \r')
cpu=$(( (c1-c0)*1000/tck ))
echo "$L scroll(gap $G): frames=$tot jank=$jank p90=$p90 p99=$p99 cpu=${cpu}ms cpu/frame=$(( tot > 0 ? cpu / tot : 0 ))ms"
$A input keyevent BACK; sleep 0.5; $A input keyevent BACK; $A input keyevent HOME
