#!/usr/bin/env python3
"""Печатает, какие ассеты реально лежат в релизах sherpa-onnx.

Нужен потому, что имена ассетов (AAR, архивы моделей) меняются между релизами,
а в среде разработки GitHub API для этого репозитория недоступен — проверить
можно только из CI.
"""
import json
import os
import urllib.request

REPO = "k2-fsa/sherpa-onnx"
INTERESTING = (
    "whisper-tiny",
    "whisper-base",
    "whisper-small",
    "silero",
    "ten-vad",
    "gtcrn",
    "punct",
)


def api(path: str):
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}{path}")
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def print_aars() -> None:
    print("=" * 70)
    print("AAR в последних релизах")
    print("=" * 70)
    for rel in api("/releases?per_page=30"):
        aars = [a for a in rel.get("assets", []) if a["name"].endswith(".aar")]
        if not aars:
            continue
        print(f"TAG {rel['tag_name']}")
        for a in aars:
            print(f"    {a['name']}  {a['size'] / 1e6:.1f} MB")
            print(f"        {a['browser_download_url']}")


def print_tag(tag: str) -> None:
    print("=" * 70)
    print(f"Ассеты тега {tag}")
    print("=" * 70)
    try:
        rel = api(f"/releases/tags/{tag}")
    except Exception as exc:  # noqa: BLE001 - разведка, любая ошибка информативна
        print(f"    недоступен: {exc}")
        return
    assets = rel.get("assets", [])
    keep = [
        a for a in assets
        if any(k in a["name"].lower() for k in INTERESTING)
    ]
    print(f"    всего {len(assets)}, релевантных {len(keep)}")
    for a in sorted(keep, key=lambda x: x["name"]):
        print(f"    {a['name']}  {a['size'] / 1e6:.1f} MB")


if __name__ == "__main__":
    print_aars()
    for tag in (
        "asr-models",
        "vad-models",
        "punctuation-models",
        "speech-enhancement-models",
    ):
        print_tag(tag)
