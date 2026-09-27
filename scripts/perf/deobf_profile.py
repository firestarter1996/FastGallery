#!/usr/bin/env python3
"""Turn an ART profile dumped from a release (R8-obfuscated) build into a source-level baseline-prof.txt.

Usage: deobf_profile.py <mapping.txt> <dumped primary.prof.txt> <out baseline-prof.txt>
Dump on the device: `cmd package dump-profiles --dump-classes-and-methods org.fossify.gallery`
-> /data/misc/profman/org.fossify.gallery-primary.prof.txt (from the SAME build the mapping belongs to).
AGP re-obfuscates src/main/baseline-prof.txt with each build's own mapping, so the result survives code changes.
R8-synthesized classes (lambdas, outlines) have no source name and are dropped; startup code is still covered by
the methods that call them.
"""
import re, sys

PRIM = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D"}
mapping, prof, outp = sys.argv[1:4]

cls_o2m = {}          # original -> minified class name (dots)
cls_m2o = {}
methods = {}          # (min class, min name, min descriptor) -> (orig class, orig name, orig descriptor)
synthetic = set()     # minified names of R8-synthesized classes
member_re = re.compile(r"^\s+(?:(\d+):(\d+):)?(\S+) ([^\s(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)$")


def desc(t, table):
    dims = t.count("[]"); base = t.replace("[]", "")
    d = PRIM.get(base) or "L" + table.get(base, base).replace(".", "/") + ";"
    return "[" * dims + d


lines = open(mapping, encoding="utf-8").read().splitlines()
# pass 1: classes
cur = None
for l in lines:
    if l and not l[0].isspace() and not l.startswith("#") and l.endswith(":") and " -> " in l:
        o, m = l[:-1].split(" -> "); cls_o2m[o] = m; cls_m2o[m] = o; cur = m
    elif cur and l.startswith("# {") and "com.android.tools.r8.synthesized" in l:
        synthetic.add(cur)
# pass 2: methods (inline frames: consecutive lines with the same minified range + name; the LAST one is the real method)
cur_o = cur_m = None
group = []


def flush():
    if not group: return
    ret, name, args, mname = group[-1]
    if "." in name:            # inlined from another class: not a real method of this class
        return
    od = "(" + "".join(desc(a, {}) for a in args) + ")" + desc(ret, {})
    md = "(" + "".join(desc(a, cls_o2m) for a in args) + ")" + desc(ret, cls_o2m)
    methods.setdefault((cur_m, mname, md), (cur_o, name, od))


prev_key = None
for l in lines:
    if l and not l[0].isspace():
        flush(); group = []; prev_key = None
        if l.endswith(":") and " -> " in l and not l.startswith("#"):
            cur_o, cur_m = l[:-1].split(" -> ")
        continue
    m = member_re.match(l)
    if not m or cur_m is None: continue
    a, b, ret, name, args, mname = m.groups()
    argl = [x for x in args.split(",") if x]
    key = (a, b, mname) if a else None
    if key is None or key != prev_key:
        flush(); group = []
    group.append((ret, name, argl, mname)); prev_key = key
flush()

out, kept, dropped = [], 0, 0
prof_re = re.compile(r"^([HSP]*)L([^;]+);(?:->([^(]+)(\(.*))?$")
for l in open(prof, encoding="utf-8"):
    l = l.strip()
    m = prof_re.match(l)
    if not m: dropped += 1; continue
    flags, c, name, d = m.groups()
    cdots = c.replace("/", ".")
    if cdots in synthetic or cdots.startswith("R8$$"): dropped += 1; continue
    oc = cls_m2o.get(cdots, cdots)
    if name is None:
        out.append(f"{flags}L{oc.replace('.', '/')};"); kept += 1; continue
    r = methods.get((cdots, name, d))
    if r is None:
        if cdots not in cls_m2o and name in ("<init>", "<clinit>") or cdots not in cls_m2o:
            # class not renamed (kept) and method not in mapping: name unchanged, only descriptor types to restore
            dd = re.sub(r"L([^;]+);", lambda x: "L" + cls_m2o.get(x.group(1).replace("/", "."), x.group(1).replace("/", ".")).replace(".", "/") + ";", d)
            out.append(f"{flags}L{oc.replace('.', '/')};->{name}{dd}"); kept += 1
        else:
            dropped += 1
        continue
    rc, rn, rd = r
    out.append(f"{flags}L{rc.replace('.', '/')};->{rn}{rd}"); kept += 1
open(outp, "w").write("\n".join(sorted(set(out))) + "\n")
print(f"kept {kept} dropped {dropped} -> {outp} ({len(set(out))} unique lines)")
