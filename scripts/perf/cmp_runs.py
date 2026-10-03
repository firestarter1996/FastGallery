#!/usr/bin/env python3
"""spread.py <outdir> [key filter] : per build|scenario the individual run values, median and min..max"""
import json, sys, statistics as st
R = json.load(open(sys.argv[1] + "/cmp_rows.json")); flt = sys.argv[2:] 
for bs in sorted(R):
    L = R[bs]; keys = sorted({k for x in L for k in x if isinstance(x.get(k), (int, float)) and k not in ("title_ok",)})
    for k in keys:
        if flt and not any(f in bs or f == k for f in flt): continue
        v = [x[k] for x in L if isinstance(x.get(k), (int, float))]
        print(f"{bs:20s} {k:14s} med {st.median(v):7.1f}  [{min(v):.0f}..{max(v):.0f}]  n={len(v)}  {[round(a) for a in v]}")
