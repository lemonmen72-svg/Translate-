#!/usr/bin/env bash
# Скачивает Android AAR sherpa-onnx в app/libs/.
#
# AAR не публикуется в Maven Central, поэтому берём его из GitHub Releases.
# Имя ассета меняется между релизами, поэтому ищем его через GitHub API, а не
# по захардкоженному URL.
set -euo pipefail

REPO="k2-fsa/sherpa-onnx"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
mkdir -p "$DEST"

auth=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

echo "Ищу AAR среди релизов $REPO ..."
url=""
for page in 1 2 3; do
  json="$(curl -sSL "${auth[@]}" \
    "https://api.github.com/repos/$REPO/releases?per_page=100&page=$page")"

  url="$(printf '%s' "$json" | python3 - <<'PY'
import json, sys, re
try:
    releases = json.load(sys.stdin)
except Exception:
    sys.exit(0)
if not isinstance(releases, list):
    sys.exit(0)

best = None
for rel in releases:
    for asset in rel.get("assets", []):
        name = asset["name"]
        if not name.endswith(".aar"):
            continue
        # Нужна полная сборка под Android, а не варианты под конкретную модель.
        if "jni" in name.lower():
            continue
        m = re.search(r"(\d+)\.(\d+)\.(\d+)", name)
        ver = tuple(int(x) for x in m.groups()) if m else (0, 0, 0)
        cand = (ver, asset["size"], name, asset["browser_download_url"])
        if best is None or cand[0] > best[0]:
            best = cand
if best:
    print(best[3])
PY
)"
  [[ -n "$url" ]] && break
done

if [[ -z "$url" ]]; then
  echo "AAR sherpa-onnx не найден в релизах $REPO." >&2
  echo "Проверьте https://github.com/$REPO/releases и положите AAR в $DEST вручную." >&2
  exit 1
fi

echo "Скачиваю $url"
curl -sSL "$url" -o "$DEST/sherpa-onnx.aar"
ls -la "$DEST"
