#!/usr/bin/env python3
"""Готовит набор моделей и манифест для релиза.

Приложение не умеет распаковывать tar.bz2: в Android нет ни tar, ни bzip2 из
коробки, а тащить на устройство ещё одну библиотеку ради разовой операции
незачем. Поэтому распаковка и конвертация делаются здесь, а в релиз уходят
готовые .onnx и .json.

Что собирается:
  * silero_vad.onnx           — VAD
  * whisper tiny/base/small   — encoder/decoder int8 плюс tokens.txt
  * gtcrn_simple.onnx         — шумоподавление (опция)
  * ct-transformer            — восстановление пунктуации для en/zh (опция)
  * opus-mt en-ru, ja-ru, zh-en — ONNX int8 плюс словари для токенизатора

Запускается по частям: сборка всех моделей разом упирается и в диск раннера, и
в лимит размера ассета релиза.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
OUT = Path("models_out")

# Потоковые модели распознавания: дают текст по ходу речи, а не после её конца.
# Это главный рычаг задержки — без них перевод не начинался, пока говорящий не
# сделает паузу.
#
# Для английского и китайского берём вариант с пунктуацией: знаки препинания
# прямо влияют на качество перевода, потому что по ним текст режется на
# предложения, а модели перевода обучены на отдельных предложениях.
STREAMING = {
    "stream-zh-en-punct": (
        "asr-models",
        "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8"
        "-2026-06-05",
    ),
    # Японского и русского в потоковом виде с пунктуацией нет, поэтому берём
    # многоязычную модель.
    "stream-multi": (
        "asr-models",
        "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
    ),
}

# Русские голоса Piper: заметно живее системного TTS Android.
# Берём int8: 21 МБ против 67 МБ, на слух разница незначительна.
VOICES = {
    "piper-ru-irina": "vits-piper-ru_RU-irina-medium-int8",
    "piper-ru-dmitri": "vits-piper-ru_RU-dmitri-medium-int8",
    "piper-ru-ruslan": "vits-piper-ru_RU-ruslan-medium-int8",
}

# Whisper: нужны многоязычные модели, а не варианты .en.
WHISPER = {
    "whisper-tiny": "sherpa-onnx-whisper-tiny",
    "whisper-base": "sherpa-onnx-whisper-base",
    "whisper-small": "sherpa-onnx-whisper-small",
}

# Пары Opus-MT.
#
# Прямой zh-ru здесь нет намеренно: проверено сборкой —
# Helsinki-NLP/opus-mt-zh-ru отдаёт RepositoryNotFoundError, такой модели не
# существует. Поэтому китайский переводится как zh-en плюс en-ru.
# Приложение выбирает цепочку по манифесту (SourceLang.opusMtChains), так что
# достаточно добавить сюда "zh-ru", если модель когда-нибудь появится, — код
# трогать не придётся.
OPUS_PAIRS = {
    "en-ru": "Helsinki-NLP/opus-mt-en-ru",
    "ja-ru": "Helsinki-NLP/opus-mt-ja-ru",
    "zh-en": "Helsinki-NLP/opus-mt-zh-en",
}


def log(*args: object) -> None:
    print(*args, flush=True)


def fetch(url: str, target: Path) -> Path:
    if target.exists():
        log(f"  уже скачан: {target.name}")
        return target
    log(f"  качаю {url}")
    target.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url, timeout=1800) as resp, open(target, "wb") as out:
        shutil.copyfileobj(resp, out)
    return target


def untar(archive: Path, dest: Path) -> Path:
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tar:
        tar.extractall(dest)
    # В архивах sherpa-onnx один корневой каталог.
    children = [p for p in dest.iterdir() if p.is_dir()]
    return children[0] if len(children) == 1 else dest


def emit(src: Path, name: str) -> None:
    """Кладёт файл в каталог выдачи под нужным именем."""
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / name
    shutil.copyfile(src, target)
    log(f"  → {name}  {target.stat().st_size / 1e6:.1f} MB")


def build_vad(work: Path) -> None:
    log("VAD")
    emit(fetch(f"{SHERPA}/asr-models/silero_vad.onnx", work / "silero_vad.onnx"),
         "silero_vad.onnx")


def build_denoiser(work: Path) -> None:
    log("Шумоподавление")
    emit(
        fetch(
            f"{SHERPA}/speech-enhancement-models/gtcrn_simple.onnx",
            work / "gtcrn_simple.onnx",
        ),
        "gtcrn_simple.onnx",
    )


def build_punct(work: Path) -> None:
    log("Пунктуация")
    name = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8"
    archive = fetch(
        f"{SHERPA}/punctuation-models/{name}.tar.bz2", work / f"{name}.tar.bz2"
    )
    root = untar(archive, work / "punct")
    model = next(root.rglob("*.onnx"))
    emit(model, "punct-ct-transformer.onnx")


def build_streaming(work: Path, only: str | None) -> None:
    """Кладёт encoder/decoder/joiner/tokens потоковой модели."""
    for key, (tag, base) in STREAMING.items():
        if only and key != only:
            continue
        log(f"Потоковый ASR: {key} ({base})")
        archive = fetch(f"{SHERPA}/{tag}/{base}.tar.bz2", work / f"{base}.tar.bz2")
        root = untar(archive, work / key)
        emit(pick(root, "encoder", "int8"), f"{key}-encoder.onnx")
        emit(pick(root, "decoder", "int8"), f"{key}-decoder.onnx")
        emit(pick(root, "joiner", "int8"), f"{key}-joiner.onnx")
        emit(next(root.rglob("tokens.txt")), f"{key}-tokens.txt")
        shutil.rmtree(root, ignore_errors=True)
        archive.unlink(missing_ok=True)


def build_voices(work: Path, only: str | None) -> None:
    """Кладёт модель голоса, токены и общий каталог espeak-ng-data одним ZIP.

    ZIP, а не tar.bz2: Android распаковывает zip штатным java.util.zip, а tar и
    bzip2 не умеет. Каталог espeak-ng-data одинаков для всех голосов Piper,
    поэтому кладём его один раз.
    """
    espeak_done = (OUT / "espeak-ng-data.zip").exists()
    for key, base in VOICES.items():
        if only and key != only:
            continue
        log(f"Голос: {key} ({base})")
        archive = fetch(f"{SHERPA}/tts-models/{base}.tar.bz2", work / f"{base}.tar.bz2")
        root = untar(archive, work / key)

        # В архиве голоса один файл модели; tokens.txt и espeak-ng-data рядом.
        candidates = sorted(root.rglob("*.onnx"))
        if not candidates:
            raise SystemExit(f"В архиве {base} нет .onnx")
        emit(candidates[0], f"{key}-model.onnx")
        emit(next(root.rglob("tokens.txt")), f"{key}-tokens.txt")

        if not espeak_done:
            data_dir = next(
                (p for p in root.rglob("espeak-ng-data") if p.is_dir()), None
            )
            if data_dir is None:
                log("  внимание: espeak-ng-data в архиве нет")
            else:
                zip_path = OUT / "espeak-ng-data.zip"
                OUT.mkdir(parents=True, exist_ok=True)
                # Архивируем содержимое каталога, а не сам каталог: на устройстве
                # ожидается путь именно к espeak-ng-data.
                shutil.make_archive(
                    str(zip_path.with_suffix("")), "zip", root_dir=str(data_dir)
                )
                log(
                    f"  → espeak-ng-data.zip  "
                    f"{zip_path.stat().st_size / 1e6:.1f} MB"
                )
                espeak_done = True

        shutil.rmtree(root, ignore_errors=True)
        archive.unlink(missing_ok=True)


def build_whisper(work: Path, only: str | None) -> None:
    for key, base in WHISPER.items():
        if only and key != only:
            continue
        log(f"Whisper: {key}")
        archive = fetch(f"{SHERPA}/asr-models/{base}.tar.bz2", work / f"{base}.tar.bz2")
        root = untar(archive, work / key)
        # В архиве лежат и fp32, и int8 варианты — берём int8.
        encoder = pick(root, "encoder", "int8")
        decoder = pick(root, "decoder", "int8")
        tokens = next(root.rglob("*tokens.txt"))
        emit(encoder, f"{key}-encoder.int8.onnx")
        emit(decoder, f"{key}-decoder.int8.onnx")
        emit(tokens, f"{key}-tokens.txt")
        # Освобождаем диск: раннеру не хватит места на все модели сразу.
        shutil.rmtree(root.parent if root.parent != work else root, ignore_errors=True)
        archive.unlink(missing_ok=True)


def pick(root: Path, part: str, flavour: str) -> Path:
    candidates = [p for p in root.rglob("*.onnx") if part in p.name]
    preferred = [p for p in candidates if flavour in p.name]
    if preferred:
        return preferred[0]
    if not candidates:
        raise SystemExit(f"В {root} нет файла с «{part}»")
    log(f"  внимание: {flavour}-варианта {part} нет, беру {candidates[0].name}")
    return candidates[0]


def build_opus(work: Path, only: str | None) -> None:
    """Экспортирует Marian в ONNX и выгружает словари для токенизатора."""
    from optimum.onnxruntime import ORTModelForSeq2SeqLM
    from onnxruntime.quantization import QuantType, quantize_dynamic
    from transformers import AutoTokenizer

    for pair, repo in OPUS_PAIRS.items():
        if only and pair != only:
            continue
        log(f"Opus-MT: {pair} ({repo})")
        export_dir = work / f"opus-{pair}"
        model = ORTModelForSeq2SeqLM.from_pretrained(
            repo, export=True, use_cache=False
        )
        model.save_pretrained(export_dir)
        tokenizer = AutoTokenizer.from_pretrained(repo)

        encoder = export_dir / "encoder_model.onnx"
        decoder = export_dir / "decoder_model.onnx"
        if not decoder.exists():
            # В части версий optimum файл называется иначе.
            decoder = next(
                p for p in export_dir.glob("decoder*model*.onnx")
                if "with_past" not in p.name and "merged" not in p.name
            )

        for src, suffix in ((encoder, "encoder"), (decoder, "decoder")):
            quantized = export_dir / f"{suffix}.int8.onnx"
            quantize_dynamic(
                model_input=str(src),
                model_output=str(quantized),
                weight_type=QuantType.QInt8,
                # Квантование внедрений заметно бьёт по качеству перевода.
                nodes_to_exclude=None,
                extra_options={"MatMulConstBOnly": True},
            )
            emit(quantized, f"opus-mt-{pair}-{suffix}.int8.onnx")

        dump_tokenizer(tokenizer, repo, pair)
        shutil.rmtree(export_dir, ignore_errors=True)


def dump_tokenizer(tokenizer: object, repo: str, pair: str) -> None:
    """Выгружает куски SentencePiece, словарь и метаданные в JSON.

    Файл .spm — protobuf; чтобы не тащить protobuf-рантайм на устройство, здесь
    куски и их логвеса выписываются в простой JSON, а инференс unigram остаётся
    в приложении.
    """
    import sentencepiece as spm
    from huggingface_hub import hf_hub_download

    # Путь к source.spm берём прямо из репозитория модели: у MarianTokenizer нет
    # стабильного атрибута с этим файлом, имена менялись между версиями
    # transformers.
    source_spm = Path(hf_hub_download(repo_id=repo, filename="source.spm"))
    log(f"  source.spm: {source_spm}")

    sp = spm.SentencePieceProcessor()
    sp.load(str(source_spm))
    pieces = [[sp.id_to_piece(i), sp.get_score(i)] for i in range(sp.get_piece_size())]
    write_json(f"opus-mt-{pair}-source.spm.json", pieces)

    vocab = tokenizer.get_vocab()
    write_json(f"opus-mt-{pair}-vocab.json", vocab)

    meta = {
        "pad_token_id": tokenizer.pad_token_id,
        "eos_token_id": tokenizer.eos_token_id,
        "unk_token_id": tokenizer.unk_token_id,
        # У Marian декодер стартует с pad-токена.
        "decoder_start_token_id": tokenizer.pad_token_id,
        "max_source_tokens": 256,
        "max_target_tokens": 200,
    }
    write_json(f"opus-mt-{pair}-meta.json", meta)


def write_json(name: str, payload: object) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / name
    target.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    log(f"  → {name}  {target.stat().st_size / 1e6:.1f} MB")


# Соответствие логических ключей манифеста именам файлов. Ключи должны совпадать
# с ModelKeys в приложении.
KEY_BY_FILE = {
    "silero_vad.onnx": "silero_vad",
    "gtcrn_simple.onnx": "gtcrn",
    "punct-ct-transformer.onnx": "punct_model",
}
for _key in WHISPER:
    KEY_BY_FILE[f"{_key}-encoder.int8.onnx"] = f"{_key}.encoder"
    KEY_BY_FILE[f"{_key}-decoder.int8.onnx"] = f"{_key}.decoder"
    KEY_BY_FILE[f"{_key}-tokens.txt"] = f"{_key}.tokens"
for _key in STREAMING:
    KEY_BY_FILE[f"{_key}-encoder.onnx"] = f"{_key}.encoder"
    KEY_BY_FILE[f"{_key}-decoder.onnx"] = f"{_key}.decoder"
    KEY_BY_FILE[f"{_key}-joiner.onnx"] = f"{_key}.joiner"
    KEY_BY_FILE[f"{_key}-tokens.txt"] = f"{_key}.tokens"
for _voice in VOICES:
    KEY_BY_FILE[f"{_voice}-model.onnx"] = f"{_voice}.model"
    KEY_BY_FILE[f"{_voice}-tokens.txt"] = f"{_voice}.tokens"
KEY_BY_FILE["espeak-ng-data.zip"] = "espeak-ng-data"
for _pair in OPUS_PAIRS:
    KEY_BY_FILE[f"opus-mt-{_pair}-encoder.int8.onnx"] = f"opus-mt-{_pair}.encoder"
    KEY_BY_FILE[f"opus-mt-{_pair}-decoder.int8.onnx"] = f"opus-mt-{_pair}.decoder"
    KEY_BY_FILE[f"opus-mt-{_pair}-source.spm.json"] = f"opus-mt-{_pair}.source_spm"
    KEY_BY_FILE[f"opus-mt-{_pair}-vocab.json"] = f"opus-mt-{_pair}.target_vocab"
    KEY_BY_FILE[f"opus-mt-{_pair}-meta.json"] = f"opus-mt-{_pair}.meta"


def build_manifest(existing: dict[str, dict] | None = None) -> None:
    """Собирает манифест по файлам в каталоге выдачи, доливая ранее собранные."""
    files: dict[str, dict] = dict(existing or {})
    for path in sorted(OUT.iterdir()):
        if path.name == "manifest.json" or not path.is_file():
            continue
        key = KEY_BY_FILE.get(path.name)
        if key is None:
            log(f"  пропускаю неизвестный файл {path.name}")
            continue
        files[key] = {
            "file": path.name,
            "size": path.stat().st_size,
            "sha256": sha256(path),
        }
    (OUT / "manifest.json").write_text(
        json.dumps({"version": 1, "files": files}, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    log(f"Манифест: {len(files)} записей")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "target",
        choices=[
            "vad", "denoiser", "punct", "whisper", "opus", "streaming", "voices",
            "manifest",
        ],
    )
    parser.add_argument("--only", help="конкретная модель или пара")
    parser.add_argument(
        "--merge-manifest",
        help="путь к манифесту предыдущего шага, записи из него сохраняются",
    )
    args = parser.parse_args()

    work = Path("models_work")
    work.mkdir(parents=True, exist_ok=True)

    if args.target == "vad":
        build_vad(work)
    elif args.target == "denoiser":
        build_denoiser(work)
    elif args.target == "punct":
        build_punct(work)
    elif args.target == "whisper":
        build_whisper(work, args.only)
    elif args.target == "opus":
        build_opus(work, args.only)
    elif args.target == "streaming":
        build_streaming(work, args.only)
    elif args.target == "voices":
        build_voices(work, args.only)
    elif args.target == "manifest":
        existing = None
        if args.merge_manifest and Path(args.merge_manifest).is_file():
            existing = json.loads(
                Path(args.merge_manifest).read_text(encoding="utf-8")
            ).get("files")
        build_manifest(existing)
        return

    build_manifest()


if __name__ == "__main__":
    sys.exit(main())
