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
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = "k2-fsa/sherpa-onnx"
DEST = Path(__file__).resolve().parent.parent / "app" / "libs"


# Число попыток и пауза: обрыв чтения ответа GitHub API уже случался
# (IncompleteRead на середине ответа), и из-за одного сетевого сбоя падала вся
# сборка APK.
ATTEMPTS = 4
BACKOFF_SECONDS = 3


def request(url: str):
    req = urllib.request.Request(url)
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    return req


def with_retries(what: str, action):
    """Выполняет action, повторяя при сетевых сбоях с растущей паузой."""
    last: Exception | None = None
    for attempt in range(1, ATTEMPTS + 1):
        try:
            return action()
        except (urllib.error.URLError, OSError, ValueError) as exc:
            last = exc
            if attempt == ATTEMPTS:
                break
            pause = BACKOFF_SECONDS * attempt
            print(
                f"{what}: попытка {attempt} не удалась ({exc}), повтор через {pause} с",
                flush=True,
            )
            time.sleep(pause)
    raise SystemExit(f"{what}: не удалось после {ATTEMPTS} попыток: {last}")


# Нужен именно вариант со статически слинкованным onnxruntime.
#
# Обычный sherpa-onnx-<ver>.aar кладёт в jniLibs собственный
# libonnxruntime.so, а он же приходит из Maven-зависимости
# com.microsoft.onnxruntime:onnxruntime-android, которая нужна для бэкенда
# перевода на Opus-MT. AGP падает с DuplicateRelativeFileException. В
# static-link варианте отдельного libonnxruntime.so нет, конфликта не возникает.
PREFIX = "sherpa-onnx-static-link-onnxruntime-"


def find_aar() -> tuple[str, str]:
    """Возвращает (имя, url) самого свежего подходящего AAR."""
    best = None
    for page in (1, 2, 3):
        # per_page=30, а не 100: ответ со ста релизами весит больше двадцати
        # мегабайт (в каждом релизе десятки ассетов), и именно на его чтении
        # рвалось соединение. Свежий AAR лежит в самых новых релизах, так что
        # тридцати заведомо достаточно.
        url = f"https://api.github.com/repos/{REPO}/releases?per_page=30&page={page}"

        def load() -> list:
            with urllib.request.urlopen(request(url), timeout=120) as resp:
                return json.load(resp)

        releases = with_retries(f"список релизов, страница {page}", load)
        if not releases:
            break
        for rel in releases:
            for asset in rel.get("assets", []):
                name = asset["name"]
                if not name.startswith(PREFIX) or not name.endswith(".aar"):
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
            f"AAR «{PREFIX}*.aar» не найден в релизах {REPO}. "
            f"Положите AAR в {DEST} вручную."
        )
    return best[2], best[3]


def main() -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    name, url = find_aar()
    target = DEST / "sherpa-onnx.aar"
    print(f"Найден {name}")
    print(f"Скачиваю {url}")

    def download() -> None:
        with urllib.request.urlopen(request(url), timeout=600) as resp:
            expected = resp.headers.get("Content-Length")
            with open(target, "wb") as out:
                shutil.copyfileobj(resp, out)
        size = target.stat().st_size
        # Обрыв посередине оставляет файл неполного размера, и без этой проверки
        # сборка пошла бы дальше с битым AAR. Сверяем с Content-Length, а когда
        # заголовка нет — хотя бы с разумным минимумом.
        if expected is not None and size != int(expected):
            raise OSError(f"скачано {size} байт вместо {expected}")
        if size < 1_000_000:
            raise OSError(f"скачано подозрительно мало: {size} байт")

    with_retries("загрузка AAR", download)
    print(f"Готово: {target} ({target.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
