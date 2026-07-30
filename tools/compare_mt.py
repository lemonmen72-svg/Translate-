#!/usr/bin/env python3
"""Сравнивает модели перевода на живых фразах.

Нужно, чтобы выбирать модель по результату, а не по репутации. Фразы взяты из
реального ролика, на котором пользователь увидел плохой перевод: там были потеря
рода («монахином, которому», «Карла никогда не видел») и полностью потерянный
смысл («и было ужасно» вместо «пришла в ужас от увиденного»).

Печатает перевод каждой модели плюс, для Opus-MT, разницу между жадным
декодированием и beam search — приложение сейчас декодирует жадно, и надо
понять, сколько это стоит.
"""
from __future__ import annotations

import sys

# Фразы из ролика. Специально включены места, где ML Kit ошибся.
CASES = [
    "She was a pure and beautiful nun who loved to pray to God and free her soul.",
    "Carla had never seen a man before, and was terrified by the sight.",
    "She looked closely and saw a man in the grass not far away.",
    "Three sisters living a secluded life as nuns on a remote farm.",
    "He told her that he had escaped from the prison and needed her help.",
    # Проверка на местоимения и род — то, на чём ML Kit сыпется.
    "The nun was frightened, but she decided to help him anyway.",
]

ZH_CASES = [
    "她是一个纯洁美丽的修女，喜欢向上帝祈祷。",
    "她仔细一看，发现不远处的草丛里有一个人。",
]

JA_CASES = [
    "彼女は神に祈ることが好きな、清らかで美しい修道女でした。",
    "彼女はよく見ると、遠くない草むらに人がいるのを見つけました。",
]


def separator(title: str) -> None:
    print()
    print("=" * 78)
    print(title)
    print("=" * 78, flush=True)


def run_marian(repo: str, cases: list[str]) -> None:
    """Opus-MT: жадное декодирование против beam search."""
    from transformers import MarianMTModel, MarianTokenizer

    separator(f"{repo}")
    try:
        tokenizer = MarianTokenizer.from_pretrained(repo)
        model = MarianMTModel.from_pretrained(repo)
    except Exception as exc:  # noqa: BLE001 — сравнение, не должно валить job
        print(f"не загрузилась: {exc}")
        return

    for text in cases:
        batch = tokenizer([text], return_tensors="pt", padding=True)
        greedy = tokenizer.batch_decode(
            model.generate(**batch, num_beams=1, max_new_tokens=128),
            skip_special_tokens=True,
        )[0]
        beam = tokenizer.batch_decode(
            model.generate(**batch, num_beams=4, max_new_tokens=128),
            skip_special_tokens=True,
        )[0]
        print(f"  ИСХОДНИК : {text}")
        print(f"  жадно    : {greedy}")
        print(f"  beam=4   : {beam}")
        if greedy != beam:
            print("  ^^^ beam search дал другой результат")
        print(flush=True)


def run_t5(repo: str, cases: list[str], prefix: str = "translate to ru: ") -> None:
    """T5 под en/ru/zh: перевод задаётся префиксом."""
    from transformers import AutoTokenizer, T5ForConditionalGeneration

    separator(f"{repo}")
    try:
        tokenizer = AutoTokenizer.from_pretrained(repo)
        model = T5ForConditionalGeneration.from_pretrained(repo)
    except Exception as exc:  # noqa: BLE001
        print(f"не загрузилась: {exc}")
        return

    for text in cases:
        batch = tokenizer([prefix + text], return_tensors="pt", padding=True)
        out = tokenizer.batch_decode(
            model.generate(**batch, num_beams=4, max_new_tokens=128),
            skip_special_tokens=True,
        )[0]
        print(f"  ИСХОДНИК : {text}")
        print(f"  beam=4   : {out}")
        print(flush=True)


def run_nllb(repo: str, cases: list[str], src: str) -> None:
    """NLLB: язык источника и цели задаются токенами."""
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    separator(f"{repo} ({src} -> rus_Cyrl)")
    try:
        tokenizer = AutoTokenizer.from_pretrained(repo, src_lang=src)
        model = AutoModelForSeq2SeqLM.from_pretrained(repo)
    except Exception as exc:  # noqa: BLE001
        print(f"не загрузилась: {exc}")
        return

    target = tokenizer.convert_tokens_to_ids("rus_Cyrl")
    for text in cases:
        batch = tokenizer([text], return_tensors="pt", padding=True)
        out = tokenizer.batch_decode(
            model.generate(
                **batch,
                forced_bos_token_id=target,
                num_beams=4,
                max_new_tokens=128,
            ),
            skip_special_tokens=True,
        )[0]
        print(f"  ИСХОДНИК : {text}")
        print(f"  beam=4   : {out}")
        print(flush=True)


def main() -> int:
    print("Сравнение моделей перевода на фразах из реального ролика.")
    print("Ожидаемые ошибки, за которыми следим: род («монахином», «Карла не")
    print("видел») и потеря смысла («и было ужасно»).", flush=True)

    run_marian("Helsinki-NLP/opus-mt-en-ru", CASES)
    run_t5("utrobinmv/t5_translate_en_ru_zh_base_200", CASES)
    run_t5("utrobinmv/t5_translate_en_ru_zh_small_1024", CASES)
    run_nllb("facebook/nllb-200-distilled-600M", CASES, "eng_Latn")

    print()
    print("### Китайский: важно, есть ли прямой перевод без английского")
    run_t5("utrobinmv/t5_translate_en_ru_zh_base_200", ZH_CASES)
    run_marian("Helsinki-NLP/opus-mt-zh-en", ZH_CASES)

    print()
    print("### Японский")
    run_marian("Helsinki-NLP/opus-mt-ja-ru", JA_CASES)
    run_nllb("facebook/nllb-200-distilled-600M", JA_CASES, "jpn_Jpan")

    return 0


if __name__ == "__main__":
    sys.exit(main())
