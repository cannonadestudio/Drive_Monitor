#!/usr/bin/env python3
"""
Buduje speedlimits.sqlite z wyciągu OpenStreetMap (.osm.pbf) dla aplikacji DriveMonitor.

Użycie:
    pip install osmium
    # wyciąg dla Polski (~2 GB) albo województwa (szybciej):
    #   https://download.geofabrik.de/europe/poland.html
    python3 build_speed_db.py poland-latest.osm.pbf speedlimits.sqlite

Wynik wgraj na telefon do: Android/data/pl.drivemonitor/files/speedlimits.sqlite
"""
import re
import sqlite3
import sys

import osmium

DRIVABLE = {
    "motorway", "motorway_link", "trunk", "trunk_link",
    "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential",
    "living_street", "service",
}

# Wartości „implicit” stosowane w Polsce w tagu maxspeed / maxspeed:type
IMPLICIT = {
    "PL:urban": 50,
    "PL:rural": 90,
    "PL:expressway": 120,
    "PL:motorway": 140,
    "PL:living_street": 20,
    "PL:zone30": 30,
    "PL:zone20": 20,
    "walk": 10,
}


def parse_maxspeed(tags):
    raw = tags.get("maxspeed") or tags.get("maxspeed:type") or tags.get("source:maxspeed")
    if not raw:
        return None
    raw = raw.strip()
    if raw in IMPLICIT:
        return IMPLICIT[raw]
    m = re.match(r"^(\d+)\s*(mph)?$", raw)
    if m:
        v = int(m.group(1))
        return round(v * 1.609) if m.group(2) else v
    m = re.match(r"^(\d+)", raw)            # np. "50;70" – bierzemy pierwszą wartość
    return int(m.group(1)) if m else None


class Handler(osmium.SimpleHandler):
    def __init__(self, cur):
        super().__init__()
        self.cur = cur
        self.batch = []
        self.count = 0

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in DRIVABLE:
            return
        speed = parse_maxspeed(w.tags)
        oneway = 1 if w.tags.get("oneway") in ("yes", "1", "-1") or hw.startswith("motorway") else 0
        try:
            pts = [(n.lat, n.lon) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        for (lat1, lon1), (lat2, lon2) in zip(pts, pts[1:]):
            if w.tags.get("oneway") == "-1":
                lat1, lon1, lat2, lon2 = lat2, lon2, lat1, lon1
            self.batch.append((
                lat1, lon1, lat2, lon2,
                min(lat1, lat2), max(lat1, lat2), min(lon1, lon2), max(lon1, lon2),
                speed, hw, oneway, w.id,
            ))
        if len(self.batch) > 100_000:
            self.flush()

    def flush(self):
        self.cur.executemany(
            "INSERT INTO segments (lat1, lon1, lat2, lon2, minlat, maxlat, minlon, maxlon,"
            " maxspeed, highway, oneway, way_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            self.batch,
        )
        self.count += len(self.batch)
        self.batch.clear()
        print(f"\r{self.count:,} odcinków", end="", flush=True)


def main(src, dst):
    db = sqlite3.connect(dst)
    cur = db.cursor()
    cur.executescript("""
        DROP TABLE IF EXISTS segments;
        CREATE TABLE segments (
            lat1 REAL, lon1 REAL, lat2 REAL, lon2 REAL,
            minlat REAL, maxlat REAL, minlon REAL, maxlon REAL,
            maxspeed INTEGER, highway TEXT, oneway INTEGER, way_id INTEGER
        );
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
    """)
    h = Handler(cur)
    h.apply_file(src, locations=True, idx="flex_mem")
    h.flush()
    print("\nIndeksowanie…")
    cur.execute("CREATE INDEX idx_bbox ON segments (minlat, minlon)")
    known = cur.execute("SELECT COUNT(*) FROM segments WHERE maxspeed IS NOT NULL").fetchone()[0]
    db.commit()
    cur.execute("VACUUM")
    db.close()
    print(f"Gotowe: {h.count:,} odcinków, z jawnym limitem: {known * 100 / max(h.count, 1):.0f}%")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
