#!/usr/bin/env python3
"""
Builds the compact catalog files in app/src/main/assets from public data:
  * d3-celestial  (BSD-3, Olaf Frohn)  – stars (Hipparcos-based), star names,
    constellation lines/names, Milky Way outline   https://github.com/ofrohn/d3-celestial
  * OpenNGC       (CC-BY-SA 4.0, Mattia Verga) – NGC/IC/Messier/Caldwell objects
                                                  https://github.com/mattiaverga/OpenNGC
Usage:  python3 build_catalog.py <d3-celestial/data dir> <OpenNGC/database_files dir> <assets out dir>
"""
import csv, json, math, os, struct, sys

D3, ONGC, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)

def ra360(lon):
    return lon % 360.0

# ---------------------------------------------------------------- stars
stars = json.load(open(os.path.join(D3, 'stars.8.json')))['features']
names = json.load(open(os.path.join(D3, 'starnames.json')))
stars.sort(key=lambda f: f['properties']['mag'])
with open(os.path.join(OUT, 'stars.bin'), 'wb') as fp:
    fp.write(struct.pack('>i', len(stars)))
    for f in stars:
        lon, lat = f['geometry']['coordinates']
        mag = f['properties']['mag']
        try:
            bv = float(f['properties']['bv'])
        except (TypeError, ValueError):
            bv = 0.6
        fp.write(struct.pack('>ffhhi', ra360(lon), lat, int(round(mag * 100)), int(round(bv * 100)), int(f['id'])))
have = {int(f['id']) for f in stars}
n_names = 0
with open(os.path.join(OUT, 'starnames.txt'), 'w', encoding='utf-8') as fp:
    for k, v in names.items():
        if int(k) not in have:
            continue
        nm = (v.get('name') or '').strip()
        bayer = (v.get('bayer') or '').strip()
        flam = (v.get('flam') or '').strip()
        if not (nm or bayer or flam):
            continue
        fp.write('|'.join([k, nm, bayer, flam, v.get('c', '')]) + '\n')
        n_names += 1
print('stars', len(stars), 'named/designated', n_names)

# ------------------------------------------------------- constellations
cons = json.load(open(os.path.join(D3, 'constellations.json')))['features']
lines = json.load(open(os.path.join(D3, 'constellations.lines.json')))['features']
seen = set()
with open(os.path.join(OUT, 'constellations.txt'), 'w', encoding='utf-8') as fp:
    for f in cons:
        p = f['properties']
        ab = f['id']
        if ab in seen:
            continue
        seen.add(ab)
        lon, lat = f['geometry']['coordinates']
        cz = p.get('cz') or p.get('name')
        fp.write('C|%s|%s|%s|%s|%.2f|%.2f\n' % (ab, cz, p.get('la') or p.get('name'), p.get('en') or '', ra360(lon), lat))
    for f in lines:
        for ls in f['geometry']['coordinates']:
            fp.write('P|%s|%s\n' % (f['id'], ';'.join('%.3f,%.3f' % (ra360(x), y) for x, y in ls)))
print('constellations', len(seen))

# ------------------------------------------------------------ Milky Way
def dp(points, tol):
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]; bx, by = points[b]
        dx, dy = bx - ax, by - ay
        L = math.hypot(dx, dy)
        dmax, idx = 0.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            d = math.hypot(px - ax, py - ay) if L == 0 else abs(dy * (px - ax) - dx * (py - ay)) / L
            if d > dmax:
                dmax, idx = d, i
        if dmax > tol and idx > 0:
            keep[idx] = True
            stack.append((a, idx)); stack.append((idx, b))
    return [p for p, k in zip(points, keep) if k]

mw = json.load(open(os.path.join(D3, 'milkyway.json')))['features']
tot = 0
with open(os.path.join(OUT, 'milkyway.txt'), 'w') as fp:
    for f in mw:
        lvl = int(f['id'][2:])
        g = f['geometry']
        polys = g['coordinates'] if g['type'] == 'MultiPolygon' else [g['coordinates']]
        for poly in polys:
            for ring in poly:
                r = dp([(x, y) for x, y in ring], 0.45)
                if len(r) < 4:
                    continue
                tot += len(r)
                fp.write('MW|%d|%s\n' % (lvl, ';'.join('%.2f,%.2f' % (ra360(x), y) for x, y in r)))
print('milky way vertices', tot)

# ------------------------------------------------------------------ DSO
# Czech names / short descriptions for well-known objects: id -> (cs name, distance, description)
LY = ' sv. let'
OV = {
 'M1': ('Krabí mlhovina', '6 500' + LY, 'Pozůstatek supernovy z roku 1054 v Býku. Uprostřed je rychle rotující pulzar.'),
 'M2': ('Kulová hvězdokupa ve Vodnáři', '37 500' + LY, 'Jedna z největších kulových hvězdokup, přes 100 000 hvězd.'),
 'M3': ('Kulová hvězdokupa v Honicích psech', '33 900' + LY, 'Jasná kulová hvězdokupa s asi půl milionem hvězd.'),
 'M4': ('Kulová hvězdokupa ve Štíru', '7 200' + LY, 'Jedna z nejbližších kulových hvězdokup, leží hned vedle Antara.'),
 'M5': ('Kulová hvězdokupa v Hadu', '24 500' + LY, 'Jedna z nejstarších a nejjasnějších kulových hvězdokup.'),
 'M6': ('Motýlí hvězdokupa', '1 600' + LY, 'Otevřená hvězdokupa ve Štíru, tvarem připomíná motýla.'),
 'M7': ('Ptolemaiova hvězdokupa', '980' + LY, 'Otevřená hvězdokupa ve Štíru viditelná i pouhým okem.'),
 'M8': ('Lagunová mlhovina', '4 100' + LY, 'Emisní mlhovina ve Střelci, oblast zrodu nových hvězd.'),
 'M11': ('Divoká kachna', '6 200' + LY, 'Bohatá otevřená hvězdokupa ve Štítu, tvarem připomíná letící kachny.'),
 'M13': ('Velká hvězdokupa v Herkulovi', '22 200' + LY, 'Nejjasnější kulová hvězdokupa severní oblohy.'),
 'M15': ('Kulová hvězdokupa v Pegasu', '33 600' + LY, 'Mimořádně hustá kulová hvězdokupa.'),
 'M16': ('Orlí mlhovina', '7 000' + LY, 'Mlhovina s hvězdokupou, součástí jsou slavné Pilíře stvoření.'),
 'M17': ('Omega (Labutí) mlhovina', '5 500' + LY, 'Jasná emisní mlhovina ve Střelci.'),
 'M20': ('Mlhovina Trifid', '5 200' + LY, 'Emisní, reflexní a temná mlhovina ve Střelci rozdělená tmavými pruhy.'),
 'M22': ('Kulová hvězdokupa ve Střelci', '10 600' + LY, 'Jedna z nejjasnějších kulových hvězdokup na obloze.'),
 'M27': ('Mlhovina Činka', '1 360' + LY, 'Planetární mlhovina v Lištičce, jasná i v malých dalekohledech.'),
 'M31': ('Galaxie v Andromedě', '2,5 mil.' + LY, 'Nejbližší velká galaxie. Z tmavého místa je vidět pouhým okem.'),
 'M32': ('Průvodní galaxie M31', '2,5 mil.' + LY, 'Malá eliptická galaxie, satelit galaxie v Andromedě.'),
 'M33': ('Galaxie v Trojúhelníku', '2,7 mil.' + LY, 'Třetí největší galaxie Místní skupiny.'),
 'M34': ('Hvězdokupa v Perseovi', '1 500' + LY, 'Otevřená hvězdokupa, dobře vidět v triedru.'),
 'M35': ('Hvězdokupa v Blížencích', '2 800' + LY, 'Velká otevřená hvězdokupa u nohy Castora.'),
 'M36': ('Hvězdokupa ve Vozce', '4 100' + LY, 'Otevřená hvězdokupa, jedna ze tří Messierových ve Vozce.'),
 'M37': ('Hvězdokupa ve Vozce', '4 500' + LY, 'Nejbohatší otevřená hvězdokupa ve Vozce.'),
 'M38': ('Hvězdokupa ve Vozce', '4 200' + LY, 'Otevřená hvězdokupa tvarem připomínající písmeno pí.'),
 'M41': ('Hvězdokupa pod Siriem', '2 300' + LY, 'Otevřená hvězdokupa pár stupňů jižně od Síria.'),
 'M42': ('Velká mlhovina v Orionu', '1 344' + LY, 'Nejjasnější emisní mlhovina na obloze, hvězdná porodnice. Vidět pouhým okem.'),
 'M43': ('De Mairanova mlhovina', '1 600' + LY, 'Část komplexu Velké mlhoviny v Orionu.'),
 'M44': ('Jesličky (Praesepe)', '610' + LY, 'Otevřená hvězdokupa v Raku, vidět pouhým okem.'),
 'M45': ('Plejády (Kuřátka)', '444' + LY, 'Nejznámější otevřená hvězdokupa, pouhým okem je vidět 6 až 9 hvězd.'),
 'M51': ('Vírová galaxie', '23 mil.' + LY, 'Spirální galaxie v Honicích psech interagující s menším průvodcem.'),
 'M52': ('Hvězdokupa v Kasiopeji', '5 000' + LY, 'Bohatá otevřená hvězdokupa.'),
 'M57': ('Prstencová mlhovina', '2 300' + LY, 'Planetární mlhovina v Lyře, malý kouřový kroužek.'),
 'M63': ('Slunečnicová galaxie', '29 mil.' + LY, 'Spirální galaxie v Honicích psech.'),
 'M64': ('Galaxie Černé oko', '17 mil.' + LY, 'Spirální galaxie s výrazným tmavým pruhem prachu.'),
 'M65': ('Galaxie ve Lvu (trojice)', '35 mil.' + LY, 'Součást Lví trojice galaxií spolu s M66 a NGC 3628.'),
 'M66': ('Galaxie ve Lvu (trojice)', '35 mil.' + LY, 'Součást Lví trojice galaxií spolu s M65 a NGC 3628.'),
 'M81': ('Bodeho galaxie', '12 mil.' + LY, 'Jasná spirální galaxie ve Velké medvědici.'),
 'M82': ('Doutníková galaxie', '12 mil.' + LY, 'Galaxie s intenzivním vznikem hvězd, sousedí s M81.'),
 'M83': ('Jižní větrníková galaxie', '15 mil.' + LY, 'Spirální galaxie v Hydře.'),
 'M87': ('Obří eliptická galaxie v Panně', '53 mil.' + LY, 'V centru je supermasivní černá díra zachycená teleskopem EHT.'),
 'M92': ('Kulová hvězdokupa v Herkulovi', '26 700' + LY, 'Jasná kulová hvězdokupa, často přehlížená vedle M13.'),
 'M97': ('Mlhovina Sova', '2 000' + LY, 'Planetární mlhovina ve Velké medvědici.'),
 'M101': ('Větrníková galaxie', '21 mil.' + LY, 'Velká spirální galaxie ve Velké medvědici viděná zpředu.'),
 'M104': ('Galaxie Sombrero', '29 mil.' + LY, 'Galaxie s výrazným tmavým prachovým pásem připomínajícím sombrero.'),
 'NGC7000': ('Mlhovina Severní Amerika', '2 590' + LY, 'Emisní mlhovina v Labuti, tvarem připomíná kontinent.'),
 'NGC6992': ('Závoj (východní část)', '2 400' + LY, 'Pozůstatek supernovy v Labuti, jemné vlákno plynu.'),
 'NGC6960': ('Závoj (západní část)', '2 400' + LY, 'Pozůstatek supernovy v Labuti, prochází jím hvězda 52 Cygni.'),
 'NGC869': ('Dvojhvězdokupa v Perseovi (h)', '7 500' + LY, 'Dvě otevřené hvězdokupy vedle sebe, vidět pouhým okem.'),
 'NGC884': ('Dvojhvězdokupa v Perseovi (chí)', '7 500' + LY, 'Dvě otevřené hvězdokupy vedle sebe, vidět pouhým okem.'),
 'NGC2244': ('Hvězdokupa v Rozetě', '5 200' + LY, 'Mladá hvězdokupa uprostřed mlhoviny Rozeta.'),
 'NGC2237': ('Mlhovina Rozeta', '5 200' + LY, 'Velká emisní mlhovina v Jednorožci.'),
 'NGC5139': ('Omega Centauri', '15 800' + LY, 'Největší a nejjasnější kulová hvězdokupa Mléčné dráhy.'),
 'NGC3372': ('Mlhovina Carina', '8 500' + LY, 'Obří emisní mlhovina s hvězdou Eta Carinae.'),
 'NGC7293': ('Mlhovina Helix (Šnek)', '650' + LY, 'Jedna z nejbližších planetárních mlhovin, ve Vodnáři.'),
 'NGC6543': ('Mlhovina Kočičí oko', '3 300' + LY, 'Planetární mlhovina v Draku se složitou strukturou.'),
 'NGC2392': ('Mlhovina Eskymák', '4 000' + LY, 'Planetární mlhovina v Blížencích.'),
 'NGC253': ('Galaxie v Sochaři', '11 mil.' + LY, 'Jasná spirální galaxie s intenzivním vznikem hvězd.'),
 'NGC104': ('47 Tucanae', '14 800' + LY, 'Druhá nejjasnější kulová hvězdokupa na obloze, vedle Malého Magellanova mračna.'),
 'NGC4565': ('Galaxie Jehla', '40 mil.' + LY, 'Spirální galaxie viděná zboku ve Vlasech Bereniky.'),
 'NGC457': ('Hvězdokupa Sova (ET)', '', 'Otevřená hvězdokupa v Kasiopeji.'),
 'NGC1499': ('Mlhovina Kalifornie', '1 000' + LY, 'Dlouhá emisní mlhovina v Perseovi.'),
 'NGC2024': ('Mlhovina Plamen', '1 350' + LY, 'Emisní mlhovina vedle hvězdy Alnitak v Orionu.'),
 'B33': ('Mlhovina Koňská hlava', '1 375' + LY, 'Temná mlhovina na pozadí mlhoviny IC 434 v Orionu.'),
 'IC405': ('Mlhovina Plamenná hvězda', '1 500' + LY, 'Emisní a reflexní mlhovina ve Vozce.'),
 'IC1805': ('Mlhovina Srdce', '7 500' + LY, 'Emisní mlhovina v Kasiopeji.'),
 'IC1848': ('Mlhovina Duše', '6 500' + LY, 'Emisní mlhovina v Kasiopeji, sousedí s Mlhovinou Srdce.'),
 'NGC6888': ('Mlhovina Půlměsíc', '5 000' + LY, 'Emisní mlhovina v Labuti kolem hvězdy Wolfa-Rayeta.'),
 'LMC': ('Velké Magellanovo mračno', '160 000' + LY, 'Satelitní galaxie Mléčné dráhy, viditelná pouhým okem z jižní polokoule.'),
 'SMC': ('Malé Magellanovo mračno', '200 000' + LY, 'Satelitní galaxie Mléčné dráhy, viditelná pouhým okem z jižní polokoule.'),
 'NGC2070': ('Mlhovina Tarantule', '160 000' + LY, 'Obří oblast vzniku hvězd ve Velkém Magellanově mračnu.'),
}

def parse_ra(s):
    h, m, sec = s.split(':')
    return 15.0 * (int(h) + int(m) / 60.0 + float(sec) / 3600.0)

def parse_dec(s):
    sign = -1.0 if s.startswith('-') else 1.0
    d, m, sec = s.lstrip('+-').split(':')
    return sign * (int(d) + int(m) / 60.0 + float(sec) / 3600.0)

def fnum(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None

def make_id(row):
    if row['M']:
        return 'M%d' % int(row['M'])
    nm = row['Name']
    i = 0
    while i < len(nm) and not nm[i].isdigit():
        i += 1
    prefix, num = nm[:i], nm[i:]
    digits = ''.join(c for c in num if c.isdigit())
    tail = num[len(digits):] if num.startswith(digits) else ''
    return prefix + str(int(digits)) + tail if digits else nm

rows = []
for fn in ('NGC.csv', 'addendum.csv'):
    rows += list(csv.DictReader(open(os.path.join(ONGC, fn), encoding='utf-8'), delimiter=';'))

out, used = [], set()
for r in rows:
    typ = r['Type']
    if typ in ('NonEx', 'Dup'):
        continue
    if not r['RA'] or not r['Dec']:
        continue
    mag = fnum(r['V-Mag'])
    if mag is None:
        mag = fnum(r['B-Mag'])
    ident = {'ESO56115': 'LMC', 'NGC292': 'SMC'}.get(make_id(r), make_id(r))
    common = [c.strip() for c in (r['Common names'] or '').split(',') if c.strip()]
    is_m = bool(r['M'])
    keep = is_m or ident in OV or bool(common) or (mag is not None and mag <= 12.5)
    if typ in ('*', 'Other', 'Nova') and not (is_m or ident in OV):
        keep = False
    if typ == '**' and not (is_m or ident in OV):
        keep = False
    if not keep or ident in used:
        continue
    used.add(ident)
    cs, dist, desc = OV.get(ident, ('', '', ''))
    en = common[0] if common else ''
    out.append((mag if mag is not None else 99.0, ident, cs, en, typ, parse_ra(r['RA']), parse_dec(r['Dec']),
                fnum(r['MajAx']) or 0.0, fnum(r['MinAx']) or 0.0, fnum(r['PosAng']) or 0.0, r['Const'], dist, desc))

out.sort(key=lambda t: t[0])
with open(os.path.join(OUT, 'dso.txt'), 'w', encoding='utf-8') as fp:
    for t in out:
        fp.write('|'.join([t[1], t[2], t[3], t[4], '%.4f' % t[5], '%.4f' % t[6], '%.1f' % t[0],
                           '%.1f' % t[7], '%.1f' % t[8], '%.0f' % t[9], t[10], t[11], t[12]]) + '\n')
missing = [k for k in OV if k not in used]
print('dso', len(out), 'czech overrides unmatched:', missing)
