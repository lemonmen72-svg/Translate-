#!/usr/bin/env python3
"""Проверяет замером, помогает ли контекст предыдущей фразы качеству перевода.

ТЗ требует учитывать контекст предыдущих фраз. Ни Opus-MT, ни ML Kit не принимают
контекст отдельным параметром, поэтому единственный доступный офлайн способ —
подать в модель предыдущее предложение вместе с текущим и взять из перевода
последнее предложение. Приём известен как document-level MT через склейку.

Приём небесплатный и небезупречный, поэтому здесь измеряются три вещи, а не
декларируется польза:

1. **Помогает ли.** Есть случаи, где без контекста перевод в принципе неверен:
   род у местоимения («It was heavy» после «She took the box» — «она была
   тяжёлой», а не «это было тяжело»), род у имени, разрешение «он/она».

2. **Не разъезжается ли разбор.** Из перевода двух предложений надо достать
   второе. Если модель на входе из двух предложений вернула не два, доставать
   нечего и приём надо откатывать на перевод без контекста. Здесь считается, как
   часто это случается.

3. **Сколько стоит.** Вход растёт вдвое, декодер порождает вдвое больше токенов.
   Печатается фактическое время обоих вариантов.

Вывод — таблица и итог: сколько случаев контекст исправил, сколько испортил,
сколько раз разбор не сошёлся и во сколько раз медленнее.
"""
from __future__ import annotations

import re
import sys
import time

REPO = "Helsinki-NLP/opus-mt-en-ru"

# Пары «контекст, фраза». Взяты два вида случаев: реальные фразы из ролика
# пользователя и классические места, где без контекста перевод неверен в принципе.
CASES: list[tuple[str, str]] = [
    # Род у местоимения: box женского рода, car мужского. Без контекста модель
    # переводит «it» безлично и род теряется.
    ("She took the box from the table.", "It was heavy."),
    ("He bought a car last year.", "It was very fast."),
    ("She found an old letter in the drawer.", "It was written in French."),
    # Род у имени: без предыдущей фразы модель не знает, что Carla — женщина.
    ("Three sisters lived on a remote farm.", "Carla was the youngest of them."),
    ("Carla was a nun who loved to pray.", "She was terrified by the sight."),
    # Разрешение «он/она» в диалоге.
    ("The nun saw a man lying in the grass.", "He told her that he had escaped."),
    ("The man asked the nun for help.", "She decided to help him anyway."),
    # Обрывок, который распознаватель отдал отдельной репликой.
    ("Carla had never seen a man before,", "and was terrified by the sight."),
    # Продолжение мысли без подлежащего.
    ("The sisters worked in the field all day.", "And then went to pray."),
]

SENTENCE_SPLIT = re.compile(r"(?<=[.!?…])\s+")


def log(*args: object) -> None:
    print(*args, flush=True)


def sentences(text: str) -> list[str]:
    parts = [p.strip() for p in SENTENCE_SPLIT.split(text.strip()) if p.strip()]
    return parts


def main() -> int:
    from transformers import AutoTokenizer, MarianMTModel

    tok = AutoTokenizer.from_pretrained(REPO)
    model = MarianMTModel.from_pretrained(REPO)

    def translate(text: str) -> tuple[str, float]:
        batch = tok([text], return_tensors="pt", padding=True)
        started = time.perf_counter()
        out = model.generate(**batch, num_beams=4, max_new_tokens=160)
        elapsed = (time.perf_counter() - started) * 1000
        return tok.batch_decode(out, skip_special_tokens=True)[0].strip(), elapsed

    differs = 0
    unsplit = 0
    plain_total = 0.0
    context_total = 0.0

    for context, phrase in CASES:
        plain, plain_ms = translate(phrase)
        joined, joined_ms = translate(f"{context} {phrase}")
        plain_total += plain_ms
        context_total += joined_ms

        parts = sentences(joined)
        # Ожидаем два предложения: перевод контекста и перевод фразы. Берём
        # последнее. Если модель склеила всё в одно, доставать нечего.
        if len(parts) >= 2:
            extracted = parts[-1]
        else:
            extracted = ""
            unsplit += 1

        log("")
        log(f"КОНТЕКСТ        : {context}")
        log(f"ФРАЗА           : {phrase}")
        log(f"без контекста   : {plain}  [{plain_ms:.0f} мс]")
        log(f"вместе          : {joined}  [{joined_ms:.0f} мс]")
        log(f"извлечено       : {extracted or '— разбор не сошёлся, откат'}")
        if extracted and extracted != plain:
            differs += 1
            log("  ~~ контекст изменил перевод фразы — смотреть глазами, что лучше")

    log("")
    log("=" * 70)
    log(f"Контекст изменил перевод: {differs} из {len(CASES)}")
    log(f"Разбор не сошёлся (нужен откат): {unsplit} из {len(CASES)}")
    log(f"Время без контекста: {plain_total:.0f} мс на {len(CASES)} фраз")
    log(f"Время с контекстом : {context_total:.0f} мс "
        f"(в {context_total / max(plain_total, 1):.2f} раза дольше)")
    log("")
    log("Решение принимается по столбцам «без контекста» и «извлечено» глазами:")
    log("автоматической метрики качества здесь нет, а BLEU на девяти фразах —")
    log("самообман. Смотрим на род и на разрешение местоимений.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
