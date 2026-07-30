#!/usr/bin/env python3
"""Скачивает Android AAR sherpa-onnx в app/libs/sherpa-onnx.aar.

AAR не публикуется в Maven Central, поэтому берём его из GitHub Releases.
Имя ассета меняется между релизами, поэтому ищем самый свежий подходящий, а не
идём по захардкоженному URL.
"""
import json
import os
import re
import shutil
import sys
import urllib.request
from pathlib import Path

REPO = "k2-fsa/sherpa-onnx"
DEST = Path(__file__).resolve().parent.parent / "app" / "libs"


def request(url: str):
    req = urllib.request.Request(url)
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    return req


def find_aar() -> tuple[str, str]:
    """Возвращает (имя, url) самого свежего AAR по версии в имени файла."""
    best = None
    for page in (1, 2, 3):
        url = f"https://api.github.com/repos/{REPO}/releases?per_page=100&page={page}"
        with urllib.request.urlopen(request(url), timeout=60) as resp:
            releases = json.load(resp)
        if not releases:
            break
        for rel in releases:
            for asset in rel.get("assets", []):
                name = asset["name"]
                if not name.endswith(".aar"):
                    continue
                m = re.search(r"(\d+)\.(\d+)\.(\d+)", name)
                version = tuple(int(x) for x in m.groups()) if m else (0, 0, 0)
                cand = (version, asset["size"], name, asset["browser_download_url"])
                if best is None or cand[0] > best[0]:
                    best = cand
        if best is not None:
            # Релизы отдаются от новых к старым, первой страницы достаточно.
            break
    if best is None:
        sys.exit(
            f"AAR sherpa-onnx не найден в релизах {REPO}. "
            f"Положите AAR в {DEST} вручную."
        )
    return best[2], best[3]


def main() -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    name, url = find_aar()
    target = DEST / "sherpa-onnx.aar"
    print(f"Найден {name}")
    print(f"Скачиваю {url}")
    with urllib.request.urlopen(request(url), timeout=600) as resp, \
            open(target, "wb") as out:
        shutil.copyfileobj(resp, out)
    print(f"Готово: {target} ({target.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
