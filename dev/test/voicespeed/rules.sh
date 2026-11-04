#!/bin/bash
# rules.sh <serial> [json]: read (no json) or change TV Setup's rules; prints the mic_* values
S=$1
A=""
if [ -n "${2:-}" ]; then A="--es set $(printf '%s' "$2" | base64)"; fi
adb -s $S shell "am broadcast --include-stopped-packages -n dev.tvremote.setup/.RulesReceiver $A" | grep -o 'data="[^"]*"' | sed 's/data="//;s/"$//' | base64 -d | python3 -I -c 'import sys,json; d=json.load(sys.stdin); print({k:v for k,v in d.items() if k.startswith("mic")})'
