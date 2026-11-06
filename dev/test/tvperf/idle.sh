#!/bin/bash
# idle.sh <serial> [secs] [keys before...]: TV Home left alone (after the keys): frames drawn and CPU ms per thread
D=$1; T=${2:-130}; shift 2                                   # 130 s: two of the widgets' 60 s ticks fall inside (15 s never saw one)
A="adb -s $D shell"
$A input keyevent HOME; sleep 4
for x in "$@"; do $A input keyevent $x; sleep 0.5; done; sleep 2
pid=$($A pidof dev.tvremote.home | tr -d '\r')
$A dumpsys gfxinfo dev.tvremote.home reset >/dev/null
snap() { $A "for f in /proc/$pid/task/*/stat; do cat \$f; done" | sed 's/^[0-9]* (\(.*\)) /\1 /' | awk '{n=$1; gsub(/ /,"_",n); print n, $13+$14}' | sort | awk '{a[$1]+=$2} END{for(k in a) print k, a[k]}' | sort; }
t0=$(snap); sleep $T; t1=$(snap)
$A dumpsys gfxinfo dev.tvremote.home | grep -E "Total frames|Janky" | tr -s ' '
join <(echo "$t0") <(echo "$t1") | awk '{d=$3-$2; if(d>0) print "  " $1, d*10 "ms"}' | sort -k2 -t' ' -rn
# Home left alone draws nothing: its main thread over 5% of the time is a fault (a post loop spun it at 100% for hours, FPT, 2026-11-01)
main=$(join <(echo "$t0") <(echo "$t1") | awk '$1=="v.tvremote.home"{print ($3-$2)*10}')
[ "${main:-0}" -gt $(( T * 50 )) ] && { echo "FAIL: main thread ${main} ms busy in ${T} s while idle"; exit 1; }
echo "idle ok: main thread ${main:-0} ms in ${T} s"
