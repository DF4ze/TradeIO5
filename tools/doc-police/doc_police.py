#!/usr/bin/env python3
"""doc-police : indexation, contrôle et archivage de la documentation (stdlib only, Python>=3.8).
Sous-commandes : index | check | archive. Config : .doc-police/config.json. Idempotent."""
import os, re, sys, json, hashlib, subprocess, argparse, datetime, posixpath

ROOT = os.getcwd()
CFG = {"docs_dir": "docs", "entry": "docs/README.md", "archive_dir": "docs/archive",
       "code_dirs": ["src", "tools"], "code_exts": [".java", ".py", ".md", ".properties", ".pine", ".yml", ".xml", ".sql"],
       "skip_dirs": ["target", ".git", "node_modules", "backup", "_to_delete", "logs"],
       "size_warn_kb": 20, "stale_days": 60, "dir_statut": {"docs/archive/": "archivé", "docs/etudes/": "journal", "docs/calibration/": "journal", "docs/known-gaps/": "gap", "docs/decisions/": "decision"},
       "no_header": ["docs/archive/README-old.md"]}
LINK = re.compile(r'\]\(([^)\s#]+)(#[^)\s]*)?\)')
DOCPATH = re.compile(r'(?<![\w/.\-])docs/[\w./\-]+?\.(?:md|html|json|pdf|odt|ods)')
TODAY = datetime.date.today().isoformat()

def load_cfg():
    p = os.path.join(ROOT, ".doc-police", "config.json")
    if os.path.exists(p):
        CFG.update(json.load(open(p, encoding="utf-8")))

def rd(p):
    return open(os.path.join(ROOT, p), encoding="utf-8", newline="").read()

def wr(p, s):
    full = os.path.join(ROOT, p)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="") as f:
        f.write(s)

def walk(base, exts=None):
    out = []
    for d, ds, fs in os.walk(os.path.join(ROOT, base)):
        ds[:] = [x for x in ds if x not in CFG["skip_dirs"]]
        for f in fs:
            if exts is None or os.path.splitext(f)[1].lower() in exts:
                out.append(os.path.relpath(os.path.join(d, f), ROOT).replace(os.sep, "/"))
    return sorted(out)

def split_fm(s):
    if s.startswith("---\n"):
        e = s.find("\n---\n", 4)
        if e > 0:
            fm = {}
            for line in s[4:e].split("\n"):
                if ":" in line:
                    k, v = line.split(":", 1); fm[k.strip()] = v.strip()
            return fm, s[e + 5:], s[4:e]
    return None, s, None

def body_hash(body):
    return hashlib.sha1(body.strip().encode("utf-8")).hexdigest()[:10]

def git(*a):
    return subprocess.run(["git", *a], cwd=ROOT, capture_output=True, text=True).stdout.strip()

def resolve(src, target):
    if re.match(r'^[a-z]+:', target) or target.startswith("#"):
        return None
    return posixpath.normpath(posixpath.join(posixpath.dirname(src), target)) if not target.startswith("/") else target.lstrip("/")

def refs(path, s):
    """(cibles md-links résolues, chemins docs/ explicites)"""
    out = set()
    for m in LINK.finditer(s):
        t = resolve(path, m.group(1))
        if t: out.add(t)
    for m in DOCPATH.finditer(s):
        out.add(m.group(0))
    return out

# ---------- index : en-têtes gérés par script ----------
def cmd_index(a):
    changed = 0; idx = {}
    for p in [x for x in walk(CFG["docs_dir"], {".md"}) if x not in CFG["no_header"]]:
        s = rd(p); fm, body, _ = split_fm(s)
        h = body_hash(body)
        if fm is None:
            fm = {"statut": next((v for k, v in CFG["dir_statut"].items() if p.startswith(k)), "courant")}
            mod = git("log", "-1", "--format=%cs", "--", p) or TODAY
        else:
            mod = fm.get("modifié_le") or TODAY
        if fm.get("empreinte") != h:  # corps modifié => nouvelle date
            if fm.get("empreinte") is not None or "modifié_le" not in fm:
                mod = fm.get("modifié_le") if fm.get("empreinte") is None and "modifié_le" in fm else (git("log", "-1", "--format=%cs", "--", p) or TODAY) if fm.get("empreinte") is None else TODAY
        fm["modifié_le"] = mod; fm["empreinte"] = h
        order = ["statut", "sujet", "vérifié_le", "remplacé_par", "archivé_le", "raison"]
        keys = [k for k in order if k in fm] + [k for k in fm if k not in order and k not in ("modifié_le", "empreinte")] + ["modifié_le", "empreinte"]
        new = "---\n" + "\n".join(f"{k}: {fm[k]}" for k in keys) + "\n---\n" + body.lstrip("\n")
        if new != s:
            changed += 1
            if not a.dry: wr(p, new)
        idx[p] = {"size": len(s.encode()), "empreinte": h, "statut": fm.get("statut")}
    if not a.dry:
        os.makedirs(os.path.join(ROOT, ".doc-police"), exist_ok=True)
        wr(".doc-police/index.json", json.dumps(idx, indent=1, ensure_ascii=False))
    print(f"index: {changed} en-tête(s) {'à mettre à jour' if a.dry else 'mis à jour'}")

# ---------- check ----------
def collect():
    docs = walk(CFG["docs_dir"])
    mds = [d for d in docs if d.endswith(".md")]
    return docs, mds

def cmd_check(a):
    docs, mds = collect(); dset = set(docs); issues = []
    dset |= {posixpath.dirname(d) for d in docs} | {posixpath.dirname(posixpath.dirname(d)) for d in docs}
    graph = {}
    for p in mds:
        s = rd(p); graph[p] = refs(p, s)
        for t in graph[p]:
            if t.startswith("docs/") or t.startswith(CFG["docs_dir"] + "/"):
                if t not in dset and not t.endswith("/"):
                    issues.append(("BROKEN_LINK", p, t))
        fm, body, _ = split_fm(s)
        if fm is None and p not in CFG["no_header"]: issues.append(("FRONTMATTER", p, "absent"))
        elif fm and fm.get("statut") not in ("courant", "journal", "archivé", "decision", "gap", "charte", "index", None):
            issues.append(("FRONTMATTER", p, "statut invalide: " + str(fm.get("statut"))))
        if fm and fm.get("vérifié_le"):
            try:
                if (datetime.date.today() - datetime.date.fromisoformat(fm["vérifié_le"])).days > CFG["stale_days"]:
                    issues.append(("STALE", p, fm["vérifié_le"]))
            except ValueError: issues.append(("FRONTMATTER", p, "vérifié_le invalide"))
        if len(s.encode()) > CFG["size_warn_kb"] * 1024:
            issues.append(("SIZE", p, f"{len(s.encode())//1024} KB"))
    # accessibilité depuis l'entrée
    seen = {CFG["entry"]}; q = [CFG["entry"]]
    while q:
        c = q.pop()
        for t in graph.get(c, ()):
            if t in graph and t not in seen: seen.add(t); q.append(t)
    for p in mds:
        if p not in seen: issues.append(("UNREACHABLE", p, ""))
    # numéros dupliqués par dossier (NN-xxx)
    nums = {}
    for p in mds:
        m = re.match(r'(\d\d)-', posixpath.basename(p))
        if m: nums.setdefault((posixpath.dirname(p), m.group(1)), []).append(p)
    for (d, n), l in nums.items():
        if len(l) > 1: issues.append(("DUP_NUMBER", d + "/" + n, ", ".join(posixpath.basename(x) for x in l)))
    # refs code -> docs
    for cd in CFG["code_dirs"]:
        for f in walk(cd, set(CFG["code_exts"])):
            if f.startswith("tools/doc-police"): continue
            try: s = rd(f)
            except Exception: continue
            for m in DOCPATH.finditer(s):
                if m.group(0) not in dset and "*" not in m.group(0):
                    issues.append(("SRC_REF_BROKEN", f, m.group(0)))
    keys = sorted(set("|".join(i) for i in issues))
    bp = os.path.join(ROOT, ".doc-police", "baseline.json")
    if a.update_baseline:
        wr(".doc-police/baseline.json", json.dumps(keys, indent=1, ensure_ascii=False)); print(f"baseline: {len(keys)} entrée(s)"); return 0
    base = set(json.load(open(bp, encoding="utf-8"))) if os.path.exists(bp) else set()
    new = [k for k in keys if k not in base]; gone = [k for k in base if k not in keys]
    for k in (keys if a.all else new): print(("NEW  " if k not in base else "     ") + k)
    print(f"check: {len(keys)} problème(s), {len(new)} nouveau(x), {len(gone)} résolu(s) vs baseline")
    return 1 if new else 0

# ---------- archive ----------
def rel(frm, to):
    return posixpath.relpath(to, posixpath.dirname(frm))

def cmd_archive(a):
    src = a.path.replace("\\", "/"); sub = a.sub
    if not os.path.exists(os.path.join(ROOT, src)): sys.exit("introuvable: " + src)
    dst = f"{CFG['archive_dir']}/{sub}/{posixpath.basename(src)}"
    if os.path.exists(os.path.join(ROOT, dst)): sys.exit("déjà archivé: " + dst)
    tracked = bool(git("ls-files", "--", src))
    os.makedirs(os.path.dirname(os.path.join(ROOT, dst)), exist_ok=True)
    if tracked: subprocess.run(["git", "mv", src, dst], cwd=ROOT, check=True)
    else: os.rename(os.path.join(ROOT, src), os.path.join(ROOT, dst))
    # 1. fichier déplacé : liens relatifs + front-matter + bandeau
    s = rd(dst)
    def fix_inner(m):
        t = resolve(src, m.group(1))
        if t is None or t == src: return m.group(0)
        return "](" + rel(dst, t) + (m.group(2) or "") + ")"
    s = LINK.sub(fix_inner, s)
    fm, body, _ = split_fm(s)
    fm = fm or {}
    fm.update({"statut": "archivé", "archivé_le": TODAY, "raison": a.reason})
    if a.by: fm["remplacé_par"] = a.by
    banner = f"> ⚠ **ARCHIVÉ le {TODAY}** — {a.reason}." + (f" Remplacé par `{a.by}`." if a.by else "") + " Ne jamais lire comme référence de l'état courant.\n\n"
    body = re.sub(r'^> ⚠ \*\*ARCHIVÉ.*\n\n', '', body.lstrip("\n"))
    m = re.match(r'(# .*\n\n?)', body)
    body = (m.group(1) + banner + body[m.end():]) if m else banner + body
    wr(dst, "---\n" + "\n".join(f"{k}: {v}" for k, v in fm.items()) + "\n---\n" + body)
    # 2. liens entrants
    n = 0
    targets = walk(CFG["docs_dir"], {".md"}) + [f for cd in CFG["code_dirs"] for f in walk(cd, set(CFG["code_exts"]))]
    for p in targets:
        if p == dst or p.startswith("tools/doc-police"): continue
        try: t = rd(p)
        except Exception: continue
        if posixpath.basename(src) not in t: continue
        def fix_in(m):
            r = resolve(p, m.group(1))
            return "](" + rel(p, dst) + (m.group(2) or "") + ")" if r == src else m.group(0)
        t2 = LINK.sub(fix_in, t) if p.endswith(".md") else t
        t2 = re.sub(re.escape(src) + r'(?![\w\-])', dst, t2)
        if t2 != t: wr(p, t2); n += 1
    # 3. index d'archive
    ar = CFG["archive_dir"] + "/README.md"
    row = f"| [`{sub}/{posixpath.basename(src)}`]({sub}/{posixpath.basename(src)}) | {TODAY} | {a.reason} | {a.by or '—'} |"
    r = rd(ar)
    if f"]({sub}/{posixpath.basename(src)})" not in r:
        if "## Index des archives" not in r:
            r = r.rstrip() + "\n\n## Index des archives\n\n| Fichier | Archivé le | Raison | Remplacé par |\n|---|---|---|---|\n"
        wr(ar, r.rstrip("\n") + "\n" + row + "\n")
    print(f"archivé: {src} -> {dst} ({n} fichier(s) réécrit(s))")

def main():
    load_cfg()
    ap = argparse.ArgumentParser(); sp = ap.add_subparsers(dest="c", required=True)
    i = sp.add_parser("index"); i.add_argument("--dry", action="store_true")
    c = sp.add_parser("check"); c.add_argument("--all", action="store_true"); c.add_argument("--update-baseline", action="store_true")
    r = sp.add_parser("archive"); r.add_argument("path"); r.add_argument("--sub", default="etudes"); r.add_argument("--reason", required=True); r.add_argument("--by", default="")
    a = ap.parse_args()
    sys.exit({"index": cmd_index, "check": cmd_check, "archive": cmd_archive}[a.c](a) or 0)

if __name__ == "__main__":
    main()
