#!/usr/bin/env python3
"""Разведка: можно ли различать голоса тем sherpa-onnx, который уже в сборке.

Пользователь просит помечать в субтитрах, кто говорит. Из среды разработки ни AAR,
ни релизы k2-fsa не видны (GitHub API отдаёт 403 для чужих репозиториев), поэтому
проверка возможна только из CI.

Печатает три вещи:
  * какие классы для работы с голосами есть в classes.jar нашего AAR — без них
    писать разделение по голосам не на чем;
  * какие модели эмбеддингов голоса и сегментации опубликованы в релизах, с
    размерами — от размера зависит, влезает ли это в приложение;
  * итоговый вывод, какой путь доступен: полная диаризация или эмбеддинги плюс
    своя кластеризация.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

REPO = "k2-fsa/sherpa-onnx"
AAR = Path("app/libs/sherpa-onnx.aar")

# Классы, на которых можно построить разделение по голосам.
WANTED_CLASSES = (
    "SpeakerEmbeddingExtractor",
    "SpeakerEmbeddingManager",
    "OfflineSpeakerDiarization",
    "FastClustering",
    "SpeakerEmbeddingExtractorConfig",
    "OfflineSpeakerDiarizationSegment",
)

# Что ищем среди ассетов релизов.
ASSET_GROUPS = {
    "Эмбеддинги голоса (кто говорит)": (
        "wespeaker",
        "3dspeaker",
        "nemo_en_titanet",
        "nemo_en_speakerverification",
        "voxceleb",
        "cnceleb",
        "eres2net",
        "cam++",
    ),
    "Сегментация речи для диаризации": (
        "pyannote-segmentation",
        "reverb-diarization",
    ),
}


def log(*args: object) -> None:
    print(*args, flush=True)


def request(url: str):
    req = urllib.request.Request(url)
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    return req


def probe_aar() -> set[str]:
    """Ищет в classes.jar нашего AAR классы для работы с голосами."""
    log("=" * 70)
    log(f"Классы в {AAR}")
    log("=" * 70)
    if not AAR.exists():
        log("AAR не найден — сначала tools/fetch_sherpa.py")
        return set()

    found: set[str] = set()
    with zipfile.ZipFile(AAR) as aar:
        jars = [n for n in aar.namelist() if n.endswith(".jar")]
        log(f"jar-файлов в AAR: {jars}")
        for jar_name in jars:
            with aar.open(jar_name) as raw:
                tmp = Path("classes-probe.jar")
                with open(tmp, "wb") as out:
                    shutil.copyfileobj(raw, out)
                with zipfile.ZipFile(tmp) as jar:
                    names = jar.namelist()
                for wanted in WANTED_CLASSES:
                    if any(wanted in n for n in names):
                        found.add(wanted)
                tmp.unlink()

    for wanted in WANTED_CLASSES:
        log(f"  {'есть ' if wanted in found else 'НЕТ  '} {wanted}")
    return found


def probe_assets() -> dict[str, list[tuple[str, float]]]:
    """Ищет в релизах модели эмбеддингов и сегментации."""
    log("")
    log("=" * 70)
    log("Модели в релизах sherpa-onnx")
    log("=" * 70)

    hits: dict[str, list[tuple[str, float]]] = {g: [] for g in ASSET_GROUPS}
    seen: set[str] = set()

    # Постраничный перебор до нужного релиза не доходит: модели эмбеддингов лежат в
    # релизе 2023 года, а с тех пор их вышло больше сотни. Поэтому теги спрашиваем
    # напрямую. Опечатка в «recongition» — в самом апстриме, не здесь.
    urls = [
        f"https://api.github.com/repos/{REPO}/releases/tags/speaker-recongition-models",
        f"https://api.github.com/repos/{REPO}/releases/tags/speaker-segmentation-models",
    ]
    urls += [
        f"https://api.github.com/repos/{REPO}/releases?per_page=30&page={p}"
        for p in (1, 2, 3, 4)
    ]

    for url in urls:
        try:
            with urllib.request.urlopen(request(url), timeout=120) as resp:
                payload = json.load(resp)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            log(f"{url.rsplit('/', 1)[-1]}: не прочитан ({exc})")
            continue
        releases = payload if isinstance(payload, list) else [payload]
        for rel in releases:
            for asset in rel.get("assets", []):
                name = asset["name"]
                if name in seen:
                    continue
                low = name.lower()
                for group, needles in ASSET_GROUPS.items():
                    if any(n in low for n in needles):
                        seen.add(name)
                        hits[group].append((name, asset["size"] / 1e6))

    for group, items in hits.items():
        log("")
        log(f"### {group}")
        if not items:
            log("  ничего не нашлось")
        for name, size_mb in sorted(items, key=lambda x: x[1])[:20]:
            log(f"  {size_mb:7.1f} MB  {name}")
    return hits


def dump_api() -> None:
    """Печатает настоящие сигнатуры классов через javap.

    Иначе код против незнакомого API пишется угадыванием имён методов, и каждая
    ошибка стоит отдельного прогона сборки. Здесь видно сразу и точно.
    """
    log("")
    log("=" * 70)
    log("Сигнатуры API")
    log("=" * 70)
    if not AAR.exists():
        return
    with zipfile.ZipFile(AAR) as aar:
        with aar.open("classes.jar") as raw, open("classes-api.jar", "wb") as out:
            shutil.copyfileobj(raw, out)

    with zipfile.ZipFile("classes-api.jar") as jar:
        names = [n for n in jar.namelist() if n.endswith(".class")]

    interesting = [
        n[:-len(".class")].replace("/", ".")
        for n in names
        if any(w in n for w in WANTED_CLASSES) and "$" not in n
    ]
    for cls in sorted(interesting):
        log("")
        log(f"--- {cls}")
        code = subprocess.run(
            ["javap", "-classpath", "classes-api.jar", cls],
            capture_output=True,
            text=True,
        )
        log(code.stdout.strip() or code.stderr.strip())


def main() -> None:
    classes = probe_aar()
    dump_api()
    assets = probe_assets()

    log("")
    log("=" * 70)
    log("ВЫВОД")
    log("=" * 70)
    if "OfflineSpeakerDiarization" in classes:
        log("Доступна готовая диаризация: сегментация плюс кластеризация внутри")
        log("sherpa-onnx. Но она офлайновая — работает по целому файлу, а не по")
        log("потоку, и для живого перевода подходит только по завершённым репликам.")
    if "SpeakerEmbeddingExtractor" in classes:
        log("Доступны эмбеддинги голоса: можно считать вектор по каждой реплике и")
        log("сопоставлять с уже виденными — это работает и в потоке.")
        if "SpeakerEmbeddingManager" in classes:
            log("Сопоставление есть в самой библиотеке (SpeakerEmbeddingManager).")
        else:
            log("Сопоставление придётся написать самому: косинусная близость и порог.")
    if not classes:
        log("В AAR нет ни одного нужного класса — различать голоса нечем, надо")
        log("либо другой AAR, либо считать эмбеддинги на onnxruntime напрямую.")

    have_embed = any(assets.get("Эмбеддинги голоса (кто говорит)", []))
    if not have_embed:
        log("Моделей эмбеддингов в релизах не нашлось — проверить вручную.")


if __name__ == "__main__":
    main()
