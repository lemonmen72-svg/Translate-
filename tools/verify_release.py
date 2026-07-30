#!/usr/bin/env python3
"""Проверяет, что релиз моделей соответствует манифесту, и дозаливает пропавшее.

Манифест обещает приложению конкретный набор файлов с размерами и контрольными
суммами. Если какого-то файла в релизе нет или размер не совпал, ошибка вылезет
только на устройстве при загрузке моделей — то есть у пользователя, а не в CI.

Один такой случай уже был: два одновременных прогона делали
`gh release upload --clobber` на один и тот же релиз и затёрли ассеты друг друга,
из-за чего пропал whisper-small-decoder, а манифест продолжал его обещать.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

TAG = "models-v1"
OUT = Path("models_out")


def release_assets() -> dict[str, int]:
    raw = subprocess.run(
        ["gh", "release", "view", TAG, "--json", "assets"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return {a["name"]: a["size"] for a in json.loads(raw)["assets"]}


def main() -> int:
    manifest_path = OUT / "manifest.json"
    if not manifest_path.is_file():
        print("Манифест не найден, проверять нечего", flush=True)
        return 0

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))["files"]
    assets = release_assets()

    broken: list[str] = []
    for key, entry in sorted(manifest.items()):
        name, size = entry["file"], entry["size"]
        actual = assets.get(name)
        if actual is None:
            print(f"ПРОПАЛ   {name}  (ключ {key})", flush=True)
            broken.append(name)
        elif actual != size:
            print(
                f"РАЗМЕР   {name}: в релизе {actual}, в манифесте {size}",
                flush=True,
            )
            broken.append(name)

    if not broken:
        print(f"Релиз в порядке: {len(manifest)} файлов на месте", flush=True)
        return 0

    print(f"\nДозаливаю {len(broken)} файлов", flush=True)
    missing_locally = [n for n in broken if not (OUT / n).is_file()]
    if missing_locally:
        print(
            "Не могу дозалить, этих файлов нет и локально: "
            + ", ".join(missing_locally),
            file=sys.stderr,
        )
        return 1

    subprocess.run(
        ["gh", "release", "upload", TAG, "--clobber", *[str(OUT / n) for n in broken]],
        check=True,
    )

    # Перепроверяем: если и после дозаливки чего-то нет, лучше упасть в CI, чем
    # отдать пользователю релиз, который приложение не сможет использовать.
    assets = release_assets()
    still_broken = [
        entry["file"]
        for entry in manifest.values()
        if assets.get(entry["file"]) != entry["size"]
    ]
    if still_broken:
        print("После дозаливки всё ещё нет: " + ", ".join(still_broken), file=sys.stderr)
        return 1

    print("Дозалито, релиз соответствует манифесту", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
