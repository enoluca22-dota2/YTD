#!/usr/bin/env python3
"""
Rebuilds app/src/main/assets/radio/stations.json, the curated Radio catalog.

Every candidate below is re-read from Radio Browser (https://www.radio-browser.info) and its stream
is actually fetched (real audio bytes, or an HLS playlist whose first segment downloads). HTTPS is
used whenever the same stream really works over it. Only stations that pass are written.

    python scripts/radio_catalog.py app/src/main/assets/radio/stations.json YYYY-MM-DD

To add a station: add (uuid, name, category, genre) to CANDIDATES (uuid from radio-browser.info).
To add a country: add it here and in RadioCountries.all (app/.../radio/RadioStation.kt).
Categories: popular, music, news, entertainment, local.
"""
import json, sys, urllib.request, urllib.parse, ssl, socket, concurrent.futures as cf

UA = "ENAGELYUCA/0.3.0 (Android; catalog check)"
API = "https://de1.api.radio-browser.info"

# (uuid, display name, category, genre label)
CANDIDATES = {
    "AL": [
        ("654c01c2-d1a3-45dd-903e-b011e1e077b6", "Radio Tirana 1", "news", "News & Talk"),
        ("a994c8b0-aaf3-4834-a145-4921b1cdd918", "Radio Tirana 2", "music", "Pop"),
        ("1203c965-72b0-44d9-93e8-0a77deb89b34", "Radio Tirana International", "news", "News"),
        ("1d12e920-2e7c-42f2-ac14-809d9192a99b", "Top Albania Radio", "popular", "Hits"),
        ("fc1d94ce-d7bf-4953-aac8-1180c5372f19", "My Music Radio", "music", "Hits"),
        ("ef1bc7cb-cc36-4bbd-a78b-4223f58c50e7", "Top Gold Radio", "music", "Classic hits"),
        ("7ac71088-cd65-417d-b437-850cc1b5fabb", "Radio Club FM", "popular", "Pop"),
        ("c2a284d3-1c1f-4eb8-b49c-4f5dff952d5a", "Radio Shqip", "popular", "Albanian music"),
        ("bb7869b2-89f9-4b53-abe8-13530e1f67a5", "Radio One", "popular", "Pop"),
        ("4ddec43d-5354-4b79-a86a-f12ed37fdf7b", "MCN Radio", "music", "Hits"),
        ("3e5317ba-b9a4-4697-a5bc-ee3b47d57ba9", "Tirana Jazz Radio", "music", "Jazz"),
        ("709ba5b8-276c-461e-975f-1b48fd98a64a", "Radio Saranda", "local", "Pop · Traditional"),
        ("1b0feabd-d3a5-4a4f-86ee-d2b4e13d8ca1", "Radio Burimi", "local", "Folk"),
        ("d366167f-96ef-4c99-9214-b9a3ab4276f5", "Chill Radio", "music", "Chill"),
        ("5b656aef-076e-4c43-b473-7b73aac61756", "Radio Aksa", "local", "Hits"),
    ],
    "GB": [
        ("98adecf7-2683-4408-9be7-02d3f9098eb8", "BBC World Service", "news", "News"),
        ("a740cc4c-9563-4fbe-a13f-e7f9e89b7441", "BBC Radio 1", "popular", "Pop"),
        ("3606ef8c-cd58-4440-8c47-dbf1e0cacdac", "BBC Radio 2", "popular", "Adult contemporary"),
        ("03e68f6d-1ca3-459f-891a-c55d84711646", "BBC Radio 3", "music", "Classical"),
        ("98137c2d-ce68-4e33-8cb7-ddf3692ecc9d", "BBC Radio 4", "news", "News & Drama"),
        ("cae35448-1285-4f12-b3e0-2f4fdde00002", "BBC Radio 5 Live", "news", "News & Sport"),
        ("96063f25-0601-11e8-ae97-52543be04c81", "Classic FM", "music", "Classical"),
        ("96200095-0601-11e8-ae97-52543be04c81", "Capital FM London", "popular", "Pop"),
        ("9608ade8-0601-11e8-ae97-52543be04c81", "Heart London", "popular", "Hits"),
        ("6efe216d-bf7e-11e9-8502-52543be04c81", "LBC", "news", "News & Talk"),
        ("0a1e0bb0-dc37-11e9-a8ba-52543be04c81", "Gold", "music", "Classic hits"),
        ("03387f8e-4a8b-4b2b-8740-5d35a5cc8c48", "Radio X", "music", "Indie & Rock"),
        ("942218e4-32f2-4131-8a73-e3432a8cf4d0", "Absolute Radio", "music", "Rock"),
        ("177dda8f-ce5f-4f18-a19e-c6c8b6f5319a", "talkSPORT", "entertainment", "Sport"),
        ("14569027-6e99-41d0-bf1e-25a0627f971a", "GB News", "news", "News"),
        ("9606ceae-0601-11e8-ae97-52543be04c81", "Radio Caroline", "entertainment", "Rock & Pop"),
        ("961f9f7f-0601-11e8-ae97-52543be04c81", "Jazz London Radio", "local", "Jazz"),
    ],
    "IT": [
        ("96185693-0601-11e8-ae97-52543be04c81", "Rai Radio 1", "news", "News"),
        ("96181819-0601-11e8-ae97-52543be04c81", "Rai Radio 2", "popular", "Pop"),
        ("9608eb3e-0601-11e8-ae97-52543be04c81", "Rai Radio 3", "music", "Classical & Culture"),
        ("675bd925-8b13-43fe-a1b9-43bb0f90fbbd", "RTL 102.5", "popular", "Hits"),
        ("9619ea14-0601-11e8-ae97-52543be04c81", "Radio 105", "popular", "Hits"),
        ("999ff951-68eb-4f4a-bec9-ed972b99d258", "Radio Deejay", "popular", "Pop"),
        ("9619eb8a-0601-11e8-ae97-52543be04c81", "Virgin Radio Italia", "music", "Rock"),
        ("961a5a75-0601-11e8-ae97-52543be04c81", "Radio Monte Carlo", "music", "Adult contemporary"),
        ("960beb61-0601-11e8-ae97-52543be04c81", "R101", "music", "Pop"),
        ("216ee092-939d-43b6-8562-c646f73ad89f", "Radio Italia", "popular", "Italian pop"),
        ("986c5985-41d7-11ea-a95e-52543be04c81", "Radio 24", "news", "News & Talk"),
        ("f8a910c3-fd87-4376-af20-1af5bfec6ab4", "m2o", "music", "Dance"),
        ("fbe60456-15e8-4db4-a1f1-4ce77b7aaecc", "Radio Capital", "music", "Classic hits"),
        ("961756e4-0601-11e8-ae97-52543be04c81", "Radio Kiss Kiss", "popular", "Pop"),
        ("9ff8c9b6-34d5-4457-befd-bdad9c218217", "Radio Sportiva", "entertainment", "Sport"),
        ("96242832-0601-11e8-ae97-52543be04c81", "Radio Radicale", "news", "Politics & Talk"),
        ("961e7e8c-0601-11e8-ae97-52543be04c81", "Radio 105 Zoo", "entertainment", "Comedy"),
    ],
}

ctx = ssl.create_default_context()

def get(url, n=65536, timeout=12, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Icy-MetaData": "1", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        data = b""
        while len(data) < n:
            chunk = r.read(min(16384, n - len(data)))
            if not chunk:
                break
            data += chunk
        return r.status, r.headers.get("Content-Type", ""), r.geturl(), data

def probe(url, depth=0):
    status, ctype, final, data = get(url)
    text = data[:4096].decode("utf-8", "ignore")
    if text.lstrip().startswith("#EXTM3U"):
        # HLS: follow the first variant / segment line.
        lines = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
        if not lines or depth > 3:
            return False, f"HLS without entries"
        nxt = urllib.parse.urljoin(final, lines[0])
        ok, info = probe(nxt, depth + 1)
        return ok, f"HLS→{info}"
    if "text/html" in ctype:
        return False, f"HTML page ({status})"
    if len(data) < 16384:
        return False, f"only {len(data)} bytes ({ctype})"
    return True, f"{status} {ctype.split(';')[0] or '?'} {len(data)//1024}KB"

def station(uuid):
    with urllib.request.urlopen(urllib.request.Request(f"{API}/json/stations/byuuid/{uuid}", headers={"User-Agent": UA}), timeout=20, context=ctx) as r:
        rows = json.load(r)
    return rows[0] if rows else None

def check(cc, uuid, name, category, genre):
    s = station(uuid)
    if not s:
        return cc, name, None, "not in directory"
    url = s["url_resolved"] or s["url"]
    # Prefer HTTPS whenever the same stream is really served over it.
    if url.startswith("http://"):
        secure = "https://" + url[len("http://"):]
        try:
            if probe(secure)[0]:
                url = secure
        except Exception:
            pass
    try:
        ok, info = probe(url)
    except Exception as e:  # noqa
        ok, info = False, f"{type(e).__name__}: {str(e)[:60]}"
    if not ok:
        return cc, name, None, info
    favicon = s.get("favicon") or None
    entry = {
        "id": uuid,
        "name": name,
        "countryCode": cc,
        "city": (s.get("state") or "").strip() or None,
        "genre": genre,
        "category": category,
        "streamUrl": url,
        "logoUrl": favicon if favicon and favicon.startswith("https://") else None,
        "websiteUrl": (s.get("homepage") or "").strip() or None,
        "codec": s.get("codec") or None,
        "bitrate": s.get("bitrate") or None,
        "hls": bool(s.get("hls")),
    }
    return cc, name, entry, info

jobs = [(cc, *c) for cc, cs in CANDIDATES.items() for c in cs]
with cf.ThreadPoolExecutor(12) as ex:
    results = list(ex.map(lambda j: check(*j), jobs))

catalog, failed = [], []
for cc, name, entry, info in results:
    print(f"{'PASS' if entry else 'FAIL'}  {cc}  {name:28s} {info}")
    (catalog if entry else failed).append(entry or name)
out = sys.argv[1] if len(sys.argv) > 1 else "catalog.json"
json.dump({"version": 1, "verifiedAt": sys.argv[2] if len(sys.argv) > 2 else None, "stations": catalog}, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(f"\n{len(catalog)} passed, {len(failed)} failed -> {out}")
