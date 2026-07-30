#!/usr/bin/env python3
"""Разведка: что реально доступно из моделей.

Нужна потому, что из среды разработки не видно ни huggingface.co, ни GitHub API
для чужих репозиториев — проверить можно только из CI.

Печатает три вещи:
  * AAR sherpa-onnx;
  * ассеты релизов sherpa-onnx по интересующим нас категориям — потоковый ASR,
    русский TTS, шумоподавление, пунктуация;
  * существование конкретных моделей перевода на Hugging Face.
"""
import json
import os
import urllib.error
import urllib.request

REPO = "k2-fsa/sherpa-onnx"

# Что ищем в именах ассетов, по категориям.
GROUPS = {
    "Потоковый ASR (для синхронного перевода)": (
        "streaming-zipformer",
        "streaming-paraformer",
        "streaming-conformer",
        "streaming-transducer",
        "streaming-nemo",
    ),
    "Офлайн ASR для русского и японского": (
        "russian",
        "giga-am",
        "-ja-",
        "japanese",
        "reazon",
    ),
    "Whisper": ("whisper-",),
    "Русский TTS": ("ru_RU", "-rus", "russian", "ru-",),
    "VAD": ("silero_vad", "ten-vad"),
    "Шумоподавление": ("gtcrn", "dpdfnet"),
    "Пунктуация": ("punct",),
}

# Модели перевода: проверяем существование. tc-big заметно крупнее и лучше
# обычных opus-mt, поэтому их хочется предпочесть.
MT_CANDIDATES = [
    "Helsinki-NLP/opus-mt-tc-big-en-ru",
    "Helsinki-NLP/opus-mt-en-ru",
    "Helsinki-NLP/opus-mt-tc-big-ja-ru",
    "Helsinki-NLP/opus-mt-ja-ru",
    "Helsinki-NLP/opus-mt-tc-big-zh-ru",
    "Helsinki-NLP/opus-mt-zh-ru",
    "Helsinki-NLP/opus-mt-tc-big-zh-en",
    "Helsinki-NLP/opus-mt-zh-en",
    "facebook/nllb-200-distilled-600M",
    "utrobinmv/t5_translate_en_ru_zh_small_1024",
    "utrobinmv/t5_translate_en_ru_zh_base_200",
]

TTS_TAGS = ("tts-models", "asr-models", "speech-enhancement-models",
            "punctuation-models")


def api(path: str):
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}{path}")
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)


def print_aars() -> None:
    print("=" * 78)
    print("AAR sherpa-onnx")
    print("=" * 78)
    for rel in api("/releases?per_page=10"):
        aars = [a for a in rel.get("assets", []) if a["name"].endswith(".aar")]
        if not aars:
            continue
        print(f"TAG {rel['tag_name']}")
        for a in aars:
            print(f"    {a['name']}  {a['size'] / 1e6:.1f} MB")
        break


def collect_assets() -> list[tuple[str, str, int]]:
    out = []
    for tag in TTS_TAGS:
        try:
            rel = api(f"/releases/tags/{tag}")
        except urllib.error.HTTPError as exc:
            print(f"тег {tag} недоступен: {exc}")
            continue
        for a in rel.get("assets", []):
            out.append((tag, a["name"], a["size"]))
    return out


def print_groups(assets: list[tuple[str, str, int]]) -> None:
    for title, needles in GROUPS.items():
        print()
        print("=" * 78)
        print(title)
        print("=" * 78)
        found = [
            (tag, name, size)
            for tag, name, size in assets
            if any(n.lower() in name.lower() for n in needles)
        ]
        if not found:
            print("    ничего не нашлось")
            continue
        for tag, name, size in sorted(found, key=lambda x: x[1]):
            print(f"    [{tag}] {name}  {size / 1e6:.1f} MB")


def print_mt() -> None:
    print()
    print("=" * 78)
    print("Модели перевода на Hugging Face")
    print("=" * 78)
    for repo in MT_CANDIDATES:
        url = f"https://huggingface.co/api/models/{repo}"
        try:
            with urllib.request.urlopen(url, timeout=60) as resp:
                info = json.load(resp)
            size = sum(
                s.get("size", 0) or 0
                for s in (info.get("siblings") or [])
            )
            print(f"    ЕСТЬ  {repo}  файлов {len(info.get('siblings') or [])}")
        except urllib.error.HTTPError as exc:
            print(f"    нет   {repo}  ({exc.code})")
        except Exception as exc:  # noqa: BLE001 — разведка
            print(f"    ?     {repo}  ({exc})")


if __name__ == "__main__":
    print_aars()
    print_groups(collect_assets())
    print_mt()
