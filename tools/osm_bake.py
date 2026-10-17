#!/usr/bin/env python3
"""Bake OpenStreetMap airport data (ODbL, (c) OpenStreetMap contributors) into compact game assets."""
import json, math, re, sys, collections
NM = 1852.0

def rdp(pts, eps):
    if len(pts) < 3: return list(range(len(pts)))
    def d(p, a, b):
        ax, ay = a; bx, by = b; px, py = p
        dx, dy = bx-ax, by-ay; L = dx*dx+dy*dy
        if L == 0: return math.hypot(px-ax, py-ay)
        t = max(0, min(1, ((px-ax)*dx+(py-ay)*dy)/L)); return math.hypot(px-ax-t*dx, py-ay-t*dy)
    keep = {0, len(pts)-1}; st = [(0, len(pts)-1)]
    while st:
        i, j = st.pop(); best, bi = 0, -1
        for k in range(i+1, j):
            v = d(pts[k], pts[i], pts[j])
            if v > best: best, bi = v, k
        if best > eps: keep.add(bi); st += [(i, bi), (bi, j)]
    return sorted(keep)

def norm_rwy(r):
    m = re.match(r'0*(\d{1,2})([LRC]?)$', r.strip()); return (m.group(1)+m.group(2)) if m else r.strip()

def bake(icao, path, out):
    data = json.load(open(path))
    nodes = {}; ways = []
    for e in data['elements']:
        if e['type'] == 'node': nodes[e['id']] = e
        elif e['type'] == 'way': ways.append(e)
    rw = [w for w in ways if w.get('tags', {}).get('aeroway') == 'runway' and w['nodes'][0] != w['nodes'][-1] and '/' in w['tags'].get('ref', '')]
    byref = collections.defaultdict(list)
    for w in rw: byref[w['tags']['ref']].append(w)
    lat0 = sum(nodes[n]['lat'] for w in rw for n in (w['nodes'][0], w['nodes'][-1])) / (2*len(rw))
    lon0 = sum(nodes[n]['lon'] for w in rw for n in (w['nodes'][0], w['nodes'][-1])) / (2*len(rw))
    k = math.cos(math.radians(lat0))
    P = lambda nid: ((nodes[nid]['lon']-lon0)*60*k, (nodes[nid]['lat']-lat0)*60)
    runways = []
    for ref, ws in byref.items():
        pts = [n for w in ws for n in w['nodes']]
        best = (0, None, None)
        ends = [pts[0], pts[-1]] + [w['nodes'][0] for w in ws] + [w['nodes'][-1] for w in ws]
        for a in ends:
            for b in ends:
                d = math.dist(P(a), P(b))
                if d > best[0]: best = (d, a, b)
        _, a, b = best
        (ax, ay), (bx, by) = P(a), P(b)
        h = math.degrees(math.atan2(bx-ax, by-ay)) % 360
        n1, n2 = [norm_rwy(s) for s in ref.split('/')[:2]]
        num1 = int(re.match(r'\d+', n1).group()) * 10
        if abs(((h - num1 + 180) % 360) - 180) > 90: (ax, ay, bx, by) = (bx, by, ax, ay); h = (h + 180) % 360
        wid = float(re.sub(r'[^\d.]', '', ws[0]['tags'].get('width', '45')) or 45) / NM
        runways.append(dict(n1=n1, n2=n2, a=(ax, ay), b=(bx, by), h=h, w=wid, ids=set(pts)))
    runways.sort(key=lambda r: r['n1'])
    # taxiways
    tw = [w for w in ways if w.get('tags', {}).get('aeroway') in ('taxiway', 'taxilane') and w['nodes'][0] != w['nodes'][-1] or
          (w.get('tags', {}).get('aeroway') in ('taxiway', 'taxilane') and w.get('tags', {}).get('area') != 'yes' and len(w['nodes']) > 1 and w['nodes'][0] != w['nodes'][-1])]
    cnt = collections.Counter(n for w in tw + rw for n in set(w['nodes']))
    hold_ids = {e['id'] for e in nodes.values() if e.get('tags', {}).get('aeroway') == 'holding_position'}
    important = set()
    for w in tw:
        important.add(w['nodes'][0]); important.add(w['nodes'][-1])
        for n in w['nodes']:
            if cnt[n] > 1 or n in hold_ids: important.add(n)
    gid = {}; gpts = []; ghold = []
    def gnode(key, xy, hold=False):
        if key not in gid: gid[key] = len(gpts); gpts.append(xy); ghold.append(hold)
        return gid[key]
    edges = {}
    labels = []
    def tname(t):
        n = t.get('ref') or t.get('name') or ''
        n = re.sub(r'(?i)^taxi(way|lane)\s+', '', n).strip()
        return n.replace(' ', '') if len(n) <= 6 else n.split()[0][:6]
    for w in tw:
        name = tname(w['tags'])
        seg = [w['nodes'][0]]
        for n in w['nodes'][1:]:
            seg.append(n)
            if n in important:
                pts = [P(i) for i in seg]
                keep = rdp(pts, 10 / NM)
                prev = None
                for kk in keep:
                    nid = seg[kk]
                    key = nid if nid in important else ('v', nid)
                    g = gnode(key, pts[kk], nid in hold_ids)
                    if prev is not None and prev != g: edges[(min(prev, g), max(prev, g))] = name
                    prev = g
                seg = [n]
        if name:
            mid = P(w['nodes'][len(w['nodes'])//2]); labels.append((name, mid))
    # largest component
    adj = collections.defaultdict(set)
    for (a, b) in edges: adj[a].add(b); adj[b].add(a)
    seen = set(); comps = []
    for s in adj:
        if s in seen: continue
        st = [s]; c = set([s]); seen.add(s)
        while st:
            u = st.pop()
            for v in adj[u]:
                if v not in seen: seen.add(v); c.add(v); st.append(v)
        comps.append(c)
    main = max(comps, key=len)
    remap = {}; out_pts = []
    for i in sorted(main): remap[i] = len(out_pts); out_pts.append(i)
    E = [(remap[a], remap[b], n) for (a, b), n in edges.items() if a in main and b in main]
    XY = [gpts[i] for i in out_pts]; HOLD = [ghold[i] for i in out_pts]
    # runway geometry helpers
    def rel(r, p):
        ax, ay = r['a']; bx, by = r['b']; L = math.dist(r['a'], r['b']); ux, uy = (bx-ax)/L, (by-ay)/L
        return ((p[0]-ax)*ux + (p[1]-ay)*uy, abs((p[0]-ax)*uy - (p[1]-ay)*ux), L)
    RW = []
    for p in XY:
        ri = -1
        for i, r in enumerate(runways):
            al, cr, L = rel(r, p)
            if -0.01 < al < L + 0.01 and cr < r['w'] / 2 + 8 / NM: ri = i
        RW.append(ri)
    nadj = collections.defaultdict(set)
    for a, b, _ in E: nadj[a].add(b); nadj[b].add(a)
    hs = {}; ex = {}
    for i, r in enumerate(runways):
        for end in (0, 1):
            name = r['n1'] if end == 0 else r['n2']
            cands = []; excs = []
            for j, p in enumerate(XY):
                if RW[j] != -1: continue
                al, cr, L = rel(r, p)
                if end == 1: al = L - al
                touches = any(RW[v] == i for v in nadj[j])
                if r['w']/2 + 15/NM < cr < 260/NM and -150/NM < al < 600/NM:
                    cands.append(((0 if touches else 1), (0 if HOLD[j] else 1), round(al * 20), j))
                if r['w']/2 + 15/NM < cr < 260/NM and 0.45*L < al < 0.97*L:
                    excs.append(((0 if touches else 1), al, j))
            if cands: hs[name] = min(cands)[3]
            if excs: ex[name] = min(excs)[2]
    # gates
    gc = [e for e in nodes.values() if e.get('tags', {}).get('aeroway') in ('gate', 'parking_position') and 'lat' in e]
    for i, g in enumerate(gc):
        if not g['tags'].get('ref'): g['tags'] = dict(g['tags'], ref=g['tags'].get('name', 'P%d' % (i + 1)), _noref=1)
    gates_only = [g for g in gc if g['tags']['aeroway'] == 'gate' and '_noref' not in g['tags']]
    pool = gates_only if len(gates_only) >= 8 else [g for g in gc if '_noref' not in g['tags']]
    if len(pool) < 8: pool = gc
    chosen = []
    if pool:
        chosen.append(pool[0])
        while len(chosen) < min(16, len(pool)):
            best = max(pool, key=lambda g: min(math.dist(P(g['id']), P(c['id'])) for c in chosen))
            chosen.append(best)
    gates = []
    for g in chosen:
        gp = P(g['id'])
        j = min(range(len(XY)), key=lambda j: math.dist(gp, XY[j]) + (1 if RW[j] != -1 else 0))
        if math.dist(gp, XY[j]) < 0.35: gates.append((g['tags']['ref'].replace(' ', ''), gp, j))
    polys = []
    for w in ways:
        a = w.get('tags', {}).get('aeroway')
        if a in ('apron', 'terminal') and w['nodes'][0] == w['nodes'][-1]:
            pts = [P(n) for n in w['nodes']]
            if a == 'apron' and len(pts) < 4: continue
            keep = rdp(pts, (15 if a == 'apron' else 8) / NM)
            if len(keep) >= 3: polys.append((a, [pts[i] for i in keep]))
    lab = []
    for n, p in labels:
        if all(not (n == m and math.dist(p, q) < 0.35) for m, q in lab): lab.append((n, p))
    f = lambda v: f"{v:.4f}"
    with open(out, 'w') as o:
        o.write(f"# {icao} airport layout. Data (c) OpenStreetMap contributors, ODbL 1.0. https://www.openstreetmap.org/copyright\n")
        for r in runways: o.write(f"R {r['n1']} {r['n2']} {f(r['a'][0])} {f(r['a'][1])} {f(r['b'][0])} {f(r['b'][1])} {f(r['w'])} {r['h']:.1f}\n")
        for i, p in enumerate(XY): o.write(f"N {f(p[0])} {f(p[1])} {RW[i]} {1 if HOLD[i] else 0}\n")
        for a, b, n in E: o.write(f"E {a} {b} {n or '-'}\n")
        for n, j in hs.items(): o.write(f"H {n} {j}\n")
        for n, j in ex.items(): o.write(f"X {n} {j}\n")
        for ref, gp, j in gates: o.write(f"G {ref} {f(gp[0])} {f(gp[1])} {j}\n")
        for a, pts in polys: o.write(("A " if a == 'apron' else "T ") + ";".join(f"{f(x)},{f(y)}" for x, y in pts) + "\n")
        for n, p in lab: o.write(f"L {n} {f(p[0])} {f(p[1])}\n")
    print(icao, 'runways', [(r['n1'], r['n2'], round(r['h'], 1)) for r in runways], 'nodes', len(XY), 'edges', len(E), 'hs', len(hs), 'exits', len(ex), 'gates', len(gates), 'polys', len(polys), 'labels', len(lab))

for icao in sys.argv[1:]:
    bake(icao, f"/workspace/osm/{icao}.json", f"/workspace/atc/app/src/main/assets/{icao}.txt")
