#!/usr/bin/env bash
# Проверка ядра NOVA без Android: компилируем core-модули и запускаем CoreCheck.
# Требуется JDK 17+ и kotlinc (Kotlin 1.9+).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/tools/build/corecheck"
KOTLINC="${KOTLINC:-kotlinc}"

if ! command -v "$KOTLINC" >/dev/null 2>&1; then
  echo "Не найден kotlinc. Укажите путь: KOTLINC=/path/to/kotlinc $0" >&2
  exit 1
fi

STDLIB="$(dirname "$(readlink -f "$KOTLINC")")/../lib/kotlin-stdlib.jar"
if [ ! -f "$STDLIB" ]; then
  STDLIB="$(find "$(dirname "$(readlink -f "$KOTLINC")")/.." -name 'kotlin-stdlib.jar' | head -1)"
fi

rm -rf "$OUT"
mkdir -p "$OUT"

"$KOTLINC" \
  "$ROOT"/app/src/main/java/com/nova/assistant/core/*.kt \
  "$ROOT"/tools/corecheck/CoreCheck.kt \
  -d "$OUT"

java -Dfile.encoding=UTF-8 -cp "$OUT:$STDLIB" corecheck.CoreCheck
