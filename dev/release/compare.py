# What code_changed.py (an app's number) and guide_stale.py (a topic to reread) count as a change: not a moved file, not a comment.
import os, re, subprocess

# Folders renamed to the names on screen (2026-10-30): a file is compared with its old path, so a move alone is no update and
# leaves no topic unread. Drop an entry once a release made after the rename is the last tag.
RENAMED = {"app/tvsetup/": "app/tv/", "app/tvhome/": "app/home/", "app/tvchannels/": "app/channels/", "app/tvgames/": "app/games/"}


def before(path):
    """The path a file had before the rename (itself when it was not moved)."""
    return next((o + path[len(p):] for p, o in RENAMED.items() if path.startswith(p)), path)


C_LIKE = (".kt", ".kts", ".java", ".gradle", ".js", ".css")
HASH = (".sh", ".py", ".pro", ".properties", ".toml")


def strip(name, text):
    """The file's lines without comments and blank lines: a path renamed in comments is no change."""
    if name.endswith(C_LIKE):
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        text = re.sub(r"(?<![:\\\"'])//.*", "", text)                 # not the // of a URL in a string
        if name.endswith((".kt", ".kts", ".java")):
            text = re.sub(r"(?m)^(package|import) .*$", "", text)       # a file moved to another package changes only these
    elif name.endswith((".xml", ".html", ".svg")):
        text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    elif name.endswith(HASH):
        text = re.sub(r"(?m)^\s*#(?!!).*$", "", text)
    return [l.rstrip() for l in text.splitlines() if l.strip()]


_moves = {}


def moves(tag, root="."):
    """New path -> path at the tag, for the files git sees as moved since (a package split: issue #8, 2026-10-31)."""
    if tag not in _moves:
        out = subprocess.run(["git", "diff", "-M", "--name-status", "--diff-filter=R", tag, "--"], cwd=root, capture_output=True, text=True).stdout
        _moves[tag] = {l.split("\t")[2]: l.split("\t")[1] for l in out.splitlines() if l.count("\t") == 2}
    return _moves[tag]


def at(tag, path, root="."):
    """The file's bytes at the tag, under its old path if it was moved since; None when it did not exist."""
    for n in dict.fromkeys(x for x in [path, before(path), moves(tag, root).get(path)] if x):
        r = subprocess.run(["git", "show", f"{tag}:{n}"], cwd=root, capture_output=True)
        if not r.returncode:
            return r.stdout
    return None


def changed(tag, specs, root="."):
    """The files of these pathspecs whose content differs from the tag beyond comments (*.md, build.sh and tests not counted)."""
    names = subprocess.run(["git", "diff", "--name-only", tag, "--"] + specs, cwd=root, capture_output=True, text=True, check=True).stdout.split()
    out = []
    for n in names:
        if n.endswith(".md") or n.endswith("/build.sh") or "/src/test/" in n or "/tests/" in n:
            continue
        try:
            with open(os.path.join(root, n), "rb") as f:
                new = f.read()
        except FileNotFoundError:
            out.append(n); continue                                   # deleted
        old = at(tag, n, root)
        if old is None:
            out.append(n); continue
        try:
            if strip(n, old.decode("utf-8")) != strip(n, new.decode("utf-8")): out.append(n)
        except UnicodeDecodeError:                                    # a binary (a picture, a .so, a jar): moved is no update
            if old != new: out.append(n)
    return out
