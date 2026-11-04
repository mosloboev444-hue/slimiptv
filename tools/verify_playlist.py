#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка IPTV-плейлиста: оставляет только потоки, которые реально отвечают.

Примеры:
    python verify_playlist.py input.m3u -o working.m3u
    python verify_playlist.py a.m3u b.m3u -o merged.m3u --workers 24
    python verify_playlist.py ru.m3u -o ru_working.m3u --ffprobe "C:/ffmpeg/bin/ffprobe.exe"

Требуется ffprobe (входит в ffmpeg). По умолчанию ищется в PATH.
"""
import argparse
import concurrent.futures
import os
import re
import shutil
import subprocess
import sys


def parse_playlist(path):
    items = []
    name = group = None
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#EXTINF"):
                m = re.search(r",([^,]*)$", line)
                name = m.group(1).strip() if m else "?"
                g = re.search(r'group-title="([^"]*)"', line)
                group = g.group(1) if g else ""
            elif not line.startswith("#"):
                if name:
                    items.append((name, group or "", line))
                name = group = None
    return items


def norm_name(name):
    n = name.lower()
    n = re.sub(r"\s*\((1080p|720p|576p|576i|480p|hd|sd)\)", "", n)
    return re.sub(r"\s+", " ", n).strip()


def probe(item, ffprobe, timeout):
    name, group, url = item
    cmd = [
        ffprobe, "-v", "error",
        "-rw_timeout", str(int(timeout * 1_000_000)),
        "-timeout", str(int(timeout * 1_000_000)),
        "-show_entries", "stream=codec_type",
        "-of", "csv=p=0",
        "-allowed_extensions", "ALL",
        url,
    ]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 4)
        out = (r.stdout or "").strip()
        if out and ("video" in out or "audio" in out):
            return item
    except Exception:
        pass
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+", help="входные .m3u")
    ap.add_argument("-o", "--output", default="working.m3u")
    ap.add_argument("--ffprobe", default="ffprobe")
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--timeout", type=float, default=8.0)
    ap.add_argument("--dedupe", action="store_true", help="убрать дубли по названию")
    args = ap.parse_args()

    if not shutil.which(args.ffprobe) and not os.path.exists(args.ffprobe):
        print("ffprobe не найден. Укажите путь через --ffprobe", file=sys.stderr)
        return 2

    seen, items = set(), []
    for path in args.inputs:
        for it in parse_playlist(path):
            if it[2] in seen:
                continue
            seen.add(it[2])
            items.append(it)
    print(f"уникальных потоков: {len(items)}", flush=True)

    ok = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as ex:
        futures = [ex.submit(probe, it, args.ffprobe, args.timeout) for it in items]
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            res = fut.result()
            if res:
                ok.append(res)
            if i % 100 == 0:
                print(f"проверено {i}/{len(items)}, рабочих {len(ok)}", flush=True)

    if args.dedupe:
        uniq = {}
        for n, g, u in ok:
            uniq.setdefault(norm_name(n), (n, g, u))
        ok = list(uniq.values())

    ok.sort(key=lambda x: (x[1], x[0].lower()))
    with open(args.output, "w", encoding="utf-8") as f:
        f.write("#EXTM3U\n")
        for name, group, url in ok:
            f.write(f'#EXTINF:-1 group-title="{group}",{name}\n{url}\n')
    print(f"готово: {args.output} — рабочих каналов {len(ok)} из {len(items)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())