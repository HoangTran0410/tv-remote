import sys, statistics, collections, re
rows = collections.defaultdict(list)
for l in open(sys.argv[1], encoding="utf-8"):
    f = l.rstrip("\n").split("\t")
    if len(f) < 9 or f[4] in ("NA", ""): continue
    try: ms = int(f[4])
    except: continue
    if ms < 0: continue   # stale log of an earlier run (the activity had not closed)
    free = f[7]
    st = "-" if not free else ("cut" if "dropped" in free else "done")
    cpu = re.findall(r"=(\d*)>(\d*)", f[8])
    c = int(cpu[0][1]) - int(cpu[0][0]) if cpu and cpu[0][0] and cpu[0][1] else None
    rows[(f[1], f[2])].append((ms, f[5].strip('"'), st, c))
for (mode, w), v in sorted(rows.items(), key=lambda x: (x[0][0], x[0][1])):
    ms = [x[0] for x in v]; picks = collections.Counter(x[1] for x in v).most_common(1)[0][0]
    st = "/".join(sorted(set(x[2] for x in v))); cpus = [x[3] for x in v if x[3] is not None]
    print(f"{mode:5} {w} n={len(v)} med={int(statistics.median(ms)):5} min={min(ms)} max={max(ms)} free={st:9} cpu_cs={int(statistics.median(cpus)) if cpus else '-':>5} pick={picks[:60]}")
