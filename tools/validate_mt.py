#!/usr/bin/env python3
"""Проверяет мой beam search и цену квантования — замером, а не чтением кода.

Beam search в приложении написан с нуля (mt/Seq2SeqOnnx.kt) и ни с чем не
сверялся. Ошибка в нём молча портит весь перевод, поэтому здесь тот же алгоритм
повторён на Python поверх экспортированного ONNX и сравнивается с эталоном
transformers. Совпало — алгоритм верен; расходится — видно, где именно.

Второй вопрос: сколько стоит int8. Энкодер выполняется один раз на предложение,
и если fp32 заметно лучше, его стоит держать в fp32 — цена всего лишь размер
файла.

Печатает по каждой фразе четыре варианта:
  * эталон transformers, beam=4;
  * мой алгоритм на ONNX fp32;
  * мой алгоритм на ONNX int8;
  * жадный поиск на ONNX int8 — чтобы видеть, что beam действительно нужен.
"""
from __future__ import annotations

import json
import math
import shutil
import sys
import unicodedata
from pathlib import Path

REPO = "Helsinki-NLP/opus-mt-en-ru"
WORK = Path("mt_validate")

CASES = [
    "She was a pure and beautiful nun who loved to pray to God and free her soul.",
    "Carla had never seen a man before, and was terrified by the sight.",
    "She looked closely and saw a man in the grass not far away.",
    "The nun was frightened, but she decided to help him anyway.",
    "He told her that he had escaped from the prison and needed her help.",
    "Three sisters living a secluded life as nuns on a remote farm.",
]

SPACE = "▁"
UNKNOWN_PENALTY = -20.0


def log(*args: object) -> None:
    print(*args, flush=True)


# --------------------------------------------------------------------------
# Токенизатор: та же динамика Витерби, что в SentencePieceUnigram.kt
# --------------------------------------------------------------------------

class Unigram:
    def __init__(self, pieces: list[tuple[str, float]]) -> None:
        self.scores = {p: s for p, s in pieces if p}
        self.max_len = max((len(p) for p in self.scores), default=1)

    def normalize(self, text: str) -> str:
        nfkc = unicodedata.normalize("NFKC", text.strip())
        if not nfkc:
            return ""
        collapsed = " ".join(nfkc.split())
        return SPACE + collapsed.replace(" ", SPACE)

    def encode(self, text: str) -> list[str]:
        s = self.normalize(text)
        if not s:
            return []
        n = len(s)
        best = [-math.inf] * (n + 1)
        back = [0] * (n + 1)
        best[0] = 0.0
        for i in range(n):
            if best[i] == -math.inf:
                continue
            limit = min(self.max_len, n - i)
            for length in range(1, limit + 1):
                piece = s[i:i + length]
                score = self.scores.get(piece)
                if score is None:
                    continue
                cand = best[i] + score
                if cand > best[i + length]:
                    best[i + length] = cand
                    back[i + length] = length
            if best[i + 1] == -math.inf:
                best[i + 1] = best[i] + UNKNOWN_PENALTY
                back[i + 1] = 1
        out = []
        idx = n
        while idx > 0:
            length = max(back[idx], 1)
            out.append(s[idx - length:idx])
            idx -= length
        out.reverse()
        return out


def decode_pieces(pieces: list[str]) -> str:
    return "".join(pieces).replace(SPACE, " ").strip()


# --------------------------------------------------------------------------
# Тот же beam search, что в Seq2SeqOnnx.kt
# --------------------------------------------------------------------------

def log_softmax_topk(row, k: int) -> list[tuple[int, float]]:
    import numpy as np

    mx = float(row.max())
    log_sum = mx + math.log(float(np.exp(row - mx).sum()))
    idx = np.argpartition(-row, min(k, len(row) - 1))[:k]
    return sorted(
        ((int(i), float(row[i]) - log_sum) for i in idx),
        key=lambda x: -x[1],
    )


def beam_search(encoder, decoder, hidden, meta, beams: int) -> list[int]:
    import numpy as np

    source_len = hidden.shape[1]
    live = [([meta["decoder_start_token_id"]], 0.0)]
    done: list[tuple[list[int], float]] = []

    def score(tokens: list[int], logprob: float) -> float:
        return logprob / max(len(tokens), 1)

    for _ in range(meta["max_target_tokens"]):
        if not live:
            break
        batch = len(live)
        step_len = len(live[0][0])
        decoder_ids = np.array([t for t, _ in live], dtype=np.int64)
        hidden_batch = np.repeat(hidden, batch, axis=0)
        mask_batch = np.ones((batch, source_len), dtype=np.int64)

        logits = decoder.run(
            None,
            {
                "input_ids": decoder_ids,
                "encoder_attention_mask": mask_batch,
                "encoder_hidden_states": hidden_batch,
            },
        )[0]

        candidates = []
        for b in range(batch):
            row = logits[b, step_len - 1, :]
            for token_id, lp in log_softmax_topk(row, beams):
                candidates.append((b, token_id, live[b][1] + lp))

        candidates.sort(key=lambda x: -x[2])
        nxt = []
        for b, token_id, lp in candidates:
            if len(nxt) >= beams:
                break
            # pad запрещён к порождению, а не завершает гипотезу: у Marian он же
            # стартовый токен декодера и стоит в bad_words_ids модели.
            if token_id == meta["pad_token_id"]:
                continue
            tokens = live[b][0] + [token_id]
            if token_id == meta["eos_token_id"]:
                done.append((tokens, lp))
            else:
                nxt.append((tokens, lp))

        if len(done) >= beams:
            best_done = max(score(t, l) for t, l in done)
            if not nxt or max(score(t, l) for t, l in nxt) <= best_done:
                break
        live = nxt

    pool = [x for x in done + live if len(x[0]) > 1]
    if not pool:
        return []
    tokens, _ = max(pool, key=lambda x: score(x[0], x[1]))
    return tokens[1:]


def translate_onnx(encoder, decoder, tokenizer_data, meta, text: str, beams: int) -> str:
    import numpy as np

    unigram, piece_to_id, id_to_piece = tokenizer_data
    pieces = unigram.encode(text)
    if not pieces:
        return ""
    limit = meta["max_source_tokens"] - 1
    ids = [piece_to_id.get(p, meta["unk_token_id"]) for p in pieces[:limit]]
    ids.append(meta["eos_token_id"])

    input_ids = np.array([ids], dtype=np.int64)
    attention = np.ones_like(input_ids)
    hidden = encoder.run(
        None, {"input_ids": input_ids, "attention_mask": attention}
    )[0]

    out_ids = beam_search(encoder, decoder, hidden, meta, beams)
    out_pieces = [
        id_to_piece.get(i, "")
        for i in out_ids
        if i not in (meta["eos_token_id"], meta["pad_token_id"])
    ]
    return decode_pieces(out_pieces)


# --------------------------------------------------------------------------

def export(quantize: bool) -> Path:
    from optimum.onnxruntime import ORTModelForSeq2SeqLM
    from onnxruntime.quantization import QuantType, quantize_dynamic

    tag = "int8" if quantize else "fp32"
    out = WORK / tag
    if (out / "encoder_model.onnx").exists():
        return out
    log(f"Экспортирую {tag}")
    model = ORTModelForSeq2SeqLM.from_pretrained(REPO, export=True, use_cache=False)
    model.save_pretrained(out)
    if quantize:
        for name in ("encoder_model", "decoder_model"):
            src = out / f"{name}.onnx"
            dst = out / f"{name}.q.onnx"
            quantize_dynamic(
                model_input=str(src),
                model_output=str(dst),
                weight_type=QuantType.QInt8,
                extra_options={"MatMulConstBOnly": True},
            )
            shutil.move(str(dst), str(src))
    return out


def load_sessions(path: Path):
    import onnxruntime as ort

    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    enc = ort.InferenceSession(str(path / "encoder_model.onnx"), opts, providers=["CPUExecutionProvider"])
    dec_path = path / "decoder_model.onnx"
    dec = ort.InferenceSession(str(dec_path), opts, providers=["CPUExecutionProvider"])
    return enc, dec


def build_tokenizer():
    import sentencepiece as spm
    from huggingface_hub import hf_hub_download
    from transformers import AutoTokenizer

    spm_path = hf_hub_download(repo_id=REPO, filename="source.spm")
    sp = spm.SentencePieceProcessor()
    sp.load(spm_path)
    pieces = [(sp.id_to_piece(i), sp.get_score(i)) for i in range(sp.get_piece_size())]
    unigram = Unigram(pieces)

    tok = AutoTokenizer.from_pretrained(REPO)
    vocab = tok.get_vocab()
    id_to_piece = {v: k for k, v in vocab.items()}
    meta = {
        "pad_token_id": tok.pad_token_id,
        "eos_token_id": tok.eos_token_id,
        "unk_token_id": tok.unk_token_id,
        "decoder_start_token_id": tok.pad_token_id,
        "max_source_tokens": 256,
        "max_target_tokens": 200,
    }
    return (unigram, vocab, id_to_piece), meta, tok


def main() -> int:
    WORK.mkdir(exist_ok=True)
    tokenizer_data, meta, tok = build_tokenizer()
    log(f"Метаданные: {json.dumps(meta, ensure_ascii=False)}")

    # Эталон
    from transformers import MarianMTModel
    reference_model = MarianMTModel.from_pretrained(REPO)

    fp32 = load_sessions(export(quantize=False))
    int8 = load_sessions(export(quantize=True))

    mismatch_beam = 0
    mismatch_int8 = 0
    greedy_differs = 0

    for text in CASES:
        batch = tok([text], return_tensors="pt", padding=True)
        ref = tok.batch_decode(
            reference_model.generate(**batch, num_beams=4, max_new_tokens=128),
            skip_special_tokens=True,
        )[0].strip()

        mine_fp32 = translate_onnx(*fp32, tokenizer_data, meta, text, beams=4).strip()
        mine_int8 = translate_onnx(*int8, tokenizer_data, meta, text, beams=4).strip()
        greedy_int8 = translate_onnx(*int8, tokenizer_data, meta, text, beams=1).strip()

        log("")
        log(f"ИСХОДНИК        : {text}")
        log(f"эталон beam=4   : {ref}")
        log(f"мой fp32 beam=4 : {mine_fp32}")
        log(f"мой int8 beam=4 : {mine_int8}")
        log(f"мой int8 жадно  : {greedy_int8}")

        if mine_fp32 != ref:
            mismatch_beam += 1
            log("  !! мой алгоритм на fp32 расходится с эталоном — ошибка в beam search")
        if mine_int8 != mine_fp32:
            mismatch_int8 += 1
            log("  ~~ int8 отличается от fp32 — это цена квантования")
        if greedy_int8 != mine_int8:
            greedy_differs += 1

    log("")
    log("=" * 70)
    log(f"Расхождений с эталоном (ошибки алгоритма): {mismatch_beam} из {len(CASES)}")
    log(f"Расхождений int8 против fp32 (цена квантования): {mismatch_int8} из {len(CASES)}")
    log(f"Где beam отличается от жадного (польза beam): {greedy_differs} из {len(CASES)}")

    if mismatch_beam:
        log("")
        log("ВЫВОД: beam search реализован неверно, надо править Seq2SeqOnnx.kt.")
        return 1
    log("")
    log("ВЫВОД: beam search совпадает с эталоном.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
