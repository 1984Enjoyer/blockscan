#!/usr/bin/env python3
"""Generate the offline map asset from alt-dmitry/RussianMap output.js."""
import gzip
import json
import math
import sys
from pathlib import Path

SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/russian-regions.js")
DEST = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("app/src/main/assets/russia-regions.map.gz")
SIMPLIFY_DEGREES = 0.015
VIEW_WIDTH = 1000.0
VIEW_HEIGHT = 330.0

source = SOURCE.read_text(encoding="utf-8")
source = source[source.index("{"):].strip().rstrip(";")
data = json.loads(source)


def rings_of(geometry):
    if geometry["type"] == "Polygon":
        return geometry["coordinates"]
    if geometry["type"] == "MultiPolygon":
        return [ring for polygon in geometry["coordinates"] for ring in polygon]
    return []


def all_points(features):
    for feature in features:
        for ring in rings_of(feature["geometry"]):
            yield from ring


def simplify(points, tolerance):
    if len(points) <= 4:
        return points
    sq_tol = tolerance * tolerance
    keep = {0, len(points) - 1}
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        ax, ay = points[first][:2]
        bx, by = points[last][:2]
        dx, dy = bx - ax, by - ay
        denom = dx * dx + dy * dy
        best_i, best_dist = None, sq_tol
        for i in range(first + 1, last):
            px, py = points[i][:2]
            if denom:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / denom))
                dist = (px - (ax + t * dx)) ** 2 + (py - (ay + t * dy)) ** 2
            else:
                dist = (px - ax) ** 2 + (py - ay) ** 2
            if dist > best_dist:
                best_i, best_dist = i, dist
        if best_i is not None:
            keep.add(best_i)
            stack.extend(((first, best_i), (best_i, last)))
    result = [points[i] for i in sorted(keep)]
    if len(result) < 4:
        return points
    return result


features = data["features"]
points = list(all_points(features))
min_lon = min(p[0] for p in points)
max_lon = max(p[0] for p in points)
min_lat = min(p[1] for p in points)
max_lat = max(p[1] for p in points)
lines = []
for feature in features:
    paths = []
    for ring in rings_of(feature["geometry"]):
        simplified = simplify(ring, SIMPLIFY_DEGREES)
        coords = []
        for lon, lat, *_ in simplified:
            x = (lon - min_lon) / (max_lon - min_lon) * VIEW_WIDTH
            y = (max_lat - lat) / (max_lat - min_lat) * VIEW_HEIGHT
            coords.append(f"{x:.1f},{y:.1f}")
        if len(coords) >= 4:
            paths.append(";".join(coords))
    if paths:
        name = feature["properties"]["name"].strip()
        lines.append(name + "\t" + "|".join(paths))

DEST.parent.mkdir(parents=True, exist_ok=True)
with DEST.open("wb") as raw:
    with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, compresslevel=9) as zipped:
        zipped.write(("\n".join(lines) + "\n").encode("utf-8"))
print(f"wrote {len(lines)} regions to {DEST} ({DEST.stat().st_size} bytes gzipped)")
