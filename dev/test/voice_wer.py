#!/usr/bin/env python3
"""Word error rate of TV Setup Voice's recognizer on commands and free searches (Vietnamese), offline on a Mac.

  python3 -m venv /tmp/vv && /tmp/vv/bin/pip install vosk numpy
  /tmp/vv/bin/python dev/test/voice_wer.py --small <dir of vosk-model-small-vn-0.4> [--big <dir of vosk-model-vn-0.4>] [--noisy]

Audio is `say -v Linh` (macOS Vietnamese TTS) at 16 kHz: clean and one voice, so it FLATTERS every number here; read the
differences between the columns, not the absolute values. --noisy adds white noise (SNR 15 dB) as a rough stress.
Columns: grammar = what VoiceService does today (phrases.json lists + "[unk]"), free = same model with its Gr.fst language model
(the APK leaves Gr.fst out), big = vosk-model-vn-0.4 free. "sel" = the fix (see main(): sure grammar text, else the free text unless
the grammar text is a command; Voice.parse is played by the sentence's kind, see main()). Sentences below: C = command, S = free search.
"""
import argparse, json, os, re, subprocess, sys, time, unicodedata, wave
from pathlib import Path
import numpy as np
from vosk import Model, KaldiRecognizer, SetLogLevel

SENT = [  # (kind, text)
    ("C", "mở youtube"), ("C", "tắt tiếng"), ("C", "kênh vtv3"), ("C", "hẹn giờ tắt 30 phút"), ("C", "tăng âm lượng"),
    ("C", "giảm âm lượng"), ("C", "ra màn hình chính"), ("C", "quay lại"), ("C", "mở netflix"), ("C", "tạm dừng"),
    ("C", "bài tiếp"), ("C", "tắt tv"), ("C", "mở cài đặt"), ("C", "kênh tiếp theo"), ("C", "chuyển sang kênh hai mươi"),
    ("S", "tìm ngày xưa anh nói"), ("S", "sơn tùng trên youtube"), ("S", "tìm phim hành động hay nhất"), ("S", "tìm bài lạc trôi"),
    ("S", "mở nhạc trữ tình trên youtube"), ("S", "tìm phim mai"), ("S", "tìm đen vâu bài đưa nhau đi trốn"), ("S", "tìm hoa hậu hoàn vũ"),
    ("S", "tìm chuyện tình xa xưa"), ("S", "mở cô gái đến từ hôm qua trên netflix"), ("S", "tìm hài tết"), ("S", "tìm thời tiết hà nội"),
    ("S", "tìm cách nấu phở"), ("S", "tìm nơi này có anh"), ("S", "tìm bóng đá việt nam"),
]
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

def fold(s):
    s = unicodedata.normalize("NFD", s.lower().replace("đ", "d"))
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", "".join(c for c in s if not unicodedata.combining(c)))).strip()

def norm(s):  # for comparing: lowercase, no punctuation
    return re.sub(r"\s+", " ", re.sub(r"[^\w ]", " ", s.lower())).strip()

def wer(ref, hyp):
    r, h = norm(ref).split(), norm(hyp).split()
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        p, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (r[i - 1] != h[j - 1]))
    return d[len(h)], len(r)

def words(fst):
    """The model's words from Gr.fst's output symbol table (same walk as app/tvvoice/build.gradle.kts `words`)."""
    import struct
    d = open(fst, "rb").read(); o = 0
    def i32():
        nonlocal o; o += 4; return struct.unpack_from("<i", d, o - 4)[0]
    def i64():
        nonlocal o; o += 8; return struct.unpack_from("<q", d, o - 8)[0]
    def st():
        nonlocal o; n = i32(); o += n; return d[o - n:o].decode("utf8")
    assert i32() == 2125659606; st(); st(); i32(); fl = i32(); i64(); i64(); i64(); i64()
    def table():
        assert i32() == 2125658996; st(); i64(); return [(st(), i64()) for _ in range(i64())]
    if fl & 1: table()
    return [w for w, _ in table()]

def grammar(model_dir):
    """VoiceService.grammar on Phrases.spoken (approximation: every text under lang.vi, app names, channel names, accents by words/fold)."""
    ph = json.load(open(ROOT / "docs/voice/phrases.json"))
    sp = ph["spoken"]["vi"]; acc = sp["words"]
    vocab = words(Path(model_dir) / "graph/Gr.fst")
    vs = set(vocab); fm = {}
    for w in vocab:
        if w[:1] not in "<!#": fm.setdefault(fold(w), []).append(w)
    texts = []
    def walk(v):
        if isinstance(v, dict): [walk(x) for x in v.values()]
        elif isinstance(v, list): [walk(x) for x in v]
        elif isinstance(v, str): texts.append(v)
    walk(ph["lang"]["vi"])
    for a in ph["apps"]: texts += a["names"]
    for c in ph["channel_names"]:
        m = re.search(r"\(\?:([^)]*)\)", c)
        for n in ([c] if not m else [c[:m.start()] + o + c[m.end():] for o in m.group(1).split("|")]):
            texts.append(re.sub(r"\\d(\{[\d,]*\}|[*+?])?|[?*+]", " ", n))
    texts += ["không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín", "mười", "mươi", "trăm"]
    out = []
    for t in texts:
        ws = []
        typed = t.lower().split()                                # a name written with accents keeps them ("nét phích"), as Phrases.spoken
        for i, w in enumerate(fold(t).split()):
            tw = typed[i] if len(typed) == len(fold(t).split()) and typed[i] != w else None
            c = [tw] if tw else [x for x in acc.get(w, "").split("|") if x] or ([w] if w in vs else fm.get(w, [])[:1])
            if c: ws.append(c)
        if ws:
            for k in range(min(3, len(ws[0]))): out.append(" ".join(c[min(k, len(c) - 1)] for c in ws))
    return list(dict.fromkeys(out)) + ["[unk]"]

def tts(text, path, noisy):
    subprocess.run(["say", "-v", "Linh", "-o", str(path), "--file-format=WAVE", "--data-format=LEI16@16000", text], check=True)
    with wave.open(str(path)) as w: pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    if noisy:
        rng = np.random.default_rng(1); p = np.mean(pcm ** 2)
        pcm = pcm + rng.normal(0, np.sqrt(p / 10 ** 1.5), pcm.shape)
    pcm = np.concatenate([np.zeros(8000), pcm, np.zeros(32000)]).clip(-32768, 32767).astype(np.int16)   # 0.5 s before, 2 s after
    return pcm.tobytes()

def run(model, pcm, gram=None):
    rec = KaldiRecognizer(model, 16000, json.dumps(gram, ensure_ascii=False)) if gram else KaldiRecognizer(model, 16000)
    rec.SetWords(True)
    t0 = time.perf_counter(); text = []; confs = []
    for i in range(0, len(pcm), 3200):
        if rec.AcceptWaveform(pcm[i:i + 3200]): text.append(json.loads(rec.Result()))
    text.append(json.loads(rec.FinalResult()))
    for t in text: confs += [w["conf"] for w in t.get("result", [])]
    return " ".join(t["text"] for t in text if t["text"]), time.perf_counter() - t0, confs

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--small", required=True); ap.add_argument("--big"); ap.add_argument("--noisy", action="store_true")
    ap.add_argument("--audio", default=str(Path(os.environ.get("TMPDIR", "/tmp")) / "voice_wer_audio"))
    a = ap.parse_args(); SetLogLevel(-1)
    Path(a.audio).mkdir(parents=True, exist_ok=True)
    t0 = time.perf_counter(); small = Model(a.small); ts = time.perf_counter() - t0
    big = None
    if a.big:
        t0 = time.perf_counter(); big = Model(a.big); tb = time.perf_counter() - t0
    g = grammar(a.small); print(f"grammar: {len(g)} phrases; small load {ts:.1f}s" + (f", big load {tb:.1f}s" if big else ""), file=sys.stderr)
    cols = ["grammar", "free"] + (["big"] if big else []); tot = {c: [0, 0, 0.0] for c in cols + ["sel"]}
    kinds = {k: {c: [0, 0] for c in cols + ["sel"]} for k in "CS"}
    for i, (k, s) in enumerate(SENT):
        pcm = tts(s, Path(a.audio) / f"{i:02d}{'n' if a.noisy else ''}.wav", a.noisy)
        res = {"grammar": run(small, pcm, g), "free": run(small, pcm)}
        if big: res["big"] = run(big, pcm)
        # the fix (VoiceService.answer + VoiceProtocol.pick): the grammar text alone when every word was heard clearly (sure); else both go
        # to TV Setup, which keeps the grammar text only if Voice.parse names a command. Parse is not run here: it is played by "kind C =
        # a command" (checked by hand with js/voice.js on this run's 15 grammar texts of searches: none parsed as a command).
        gt, gc = res["grammar"][0], res["grammar"][2]
        sure = len(gc) > 0 and min(gc) >= 0.9 and sum(gc) / len(gc) >= 0.95
        res["sel"] = (gt if sure or k == "C" or not res["free"][0] else res["free"][0], 0.0)
        line = [f"{k} {s!r:40}"]
        for c in cols + ["sel"]:
            e, n = wer(s, res[c][0]); tot[c][0] += e; tot[c][1] += n; tot[c][2] += res[c][1]; kinds[k][c][0] += e; kinds[k][c][1] += n
            line.append(f"{c}={res[c][0]!r}")
        print(" | ".join(line))
    print(f"\n{'WER %':10}" + "".join(f"{c:>10}" for c in cols + ["sel"]))
    for k, nm in (("C", "commands"), ("S", "searches")): print(f"{nm:10}" + "".join(f"{100 * kinds[k][c][0] / kinds[k][c][1]:10.0f}" for c in cols + ["sel"]))
    print(f"{'all':10}" + "".join(f"{100 * tot[c][0] / tot[c][1]:10.0f}" for c in cols + ["sel"]))
    print(f"{'decode s':10}" + "".join(f"{tot[c][2] / len(SENT):10.2f}" for c in cols + ["sel"]) + "  (per sentence, ~4 s audio incl. 2.5 s padding)")

if __name__ == "__main__": main()
