# -*- coding: utf-8 -*-
"""Двухпроходная проверка плейлистов: оставляем потоки, стабильно отвечающие дважды."""
import subprocess, concurrent.futures, os, re, json

FFPROBE = r"C:\Users\79157\Desktop\slimbox\video\tools\ffmpeg-master-latest-win64-gpl\bin\ffprobe.exe"
BASE = r"C:\Users\79157\Desktop\slimbox\playlists"
SOURCES = [
    ("freetv_russia.m3u", 0),
    ("ngrch_ru_fresh.m3u", 1),
    ("iptvorg_ru_fresh.m3u", 2),
]

def parse(path, priority):
    items = []
    extinf = None
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#EXTINF"):
                extinf = line
            elif not line.startswith("#") and extinf:
                m = re.search(r",([^,]*)$", extinf)
                name = m.group(1).strip() if m else "?"
                g = re.search(r'group-title="([^"]*)"', extinf)
                group = g.group(1) if g else ""
                logo = re.search(r'tvg-logo="([^"]*)"', extinf)
                chno = re.search(r'tvg-chno="(\d+)"', extinf)
                items.append({
                    "name": name,
                    "group": group,
                    "url": line,
                    "logo": logo.group(1) if logo else "",
                    "chno": int(chno.group(1)) if chno else 9999,
                    "priority": priority,
                })
                extinf = None
    return items

def probe(url, timeout=9):
    cmd = [FFPROBE, "-v", "error",
           "-rw_timeout", str(int(timeout * 1_000_000)),
           "-timeout", str(int(timeout * 1_000_000)),
           "-show_entries", "stream=codec_type", "-of", "csv=p=0",
           "-allowed_extensions", "ALL", url]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 4)
        out = (r.stdout or "").strip()
        return bool(out) and ("video" in out or "audio" in out)
    except Exception:
        return False

def probe_all(items, workers=24, label=""):
    ok = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(probe, it["url"]): it for it in items}
        done = 0
        for fut in concurrent.futures.as_completed(futs):
            done += 1
            if fut.result():
                ok.append(futs[fut])
            if done % 100 == 0:
                print("%s проверено %d/%d, рабочих %d" % (label, done, len(items), len(ok)), flush=True)
    return ok

# сбор
seen, items = set(), []
for fname, pr in SOURCES:
    p = os.path.join(BASE, fname)
    if not os.path.exists(p):
        continue
    for it in parse(p, pr):
        if it["url"] in seen:
            continue
        seen.add(it["url"])
        items.append(it)
print("уникальных потоков:", len(items), flush=True)

pass1 = probe_all(items, 24, "проход 1:")
print("первый проход прошли:", len(pass1), flush=True)
pass2 = probe_all(pass1, 16, "проход 2:")
print("оба прохода прошли:", len(pass2), flush=True)

# приоритет: curated Free-TV -> ngrch -> iptv-org; внутри — по номеру канала/имени
pass2.sort(key=lambda x: (x["priority"], x["chno"], x["name"].lower()))
out = os.path.join(BASE, "ru_always.m3u")
with open(out, "w", encoding="utf-8") as f:
    f.write("#EXTM3U\n")
    for it in pass2:
        attrs = ' group-title="%s"' % (it["group"] or "Other")
        if it["logo"]:
            attrs = ' tvg-logo="%s"' % it["logo"] + attrs
        f.write("#EXTINF:-1%s,%s\n%s\n" % (attrs, it["name"], it["url"]))
print("готово:", out, "каналов:", len(pass2), flush=True)