#!/usr/bin/env python3
"""Проверка импортов и внешних вызовов в изменённых файлах.

Запуск из корня репозитория:
    python tools/check_imports.py
    python tools/check_imports.py --external

Первый режим ищет неиспользуемые импорты в изменённых Kotlin-файлах.
Второй — перечисляет файлы с сетевыми/файловыми вызовами и проверяет, что в
файле есть обработка ошибок (`try`, `runCatching` или `catch`).

Скрипт намеренно не правит код: он только отчёт. Это позволяет запускать его
в pre-push и не бояться, что эвристика что-то сломает.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", re.MULTILINE)
EXTERNAL_MARKERS = (
    "HttpURLConnection",
    "openConnection",
    "Socket(",
    "SSLSocketFactory",
    "getSharedPreferences",
    "URL(",
)


def git(*args: str) -> list[str]:
    result = subprocess.run(
        ["git", *args],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def changed_kotlin_files() -> list[Path]:
    tracked = git("diff", "--name-only", "--diff-filter=ACM")
    untracked = git("ls-files", "--others", "--exclude-standard")
    paths = {line for line in tracked + untracked if line.endswith(".kt")}
    return [Path(path) for path in sorted(paths) if Path(path).is_file()]


def unused_imports(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    body = "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("import ")
    )
    unused: list[str] = []
    for match in IMPORT_RE.finditer(text):
        full_name, alias = match.groups()
        if full_name.endswith("*"):
            continue
        name = alias or full_name.rsplit(".", 1)[-1]
        if re.search(rf"\b{re.escape(name)}\b", body) is None:
            unused.append(full_name)
    return unused


def external_audit(paths: list[Path]) -> list[str]:
    report: list[str] = []
    for path in paths:
        text = path.read_text(encoding="utf-8", errors="replace")
        hits = [marker for marker in EXTERNAL_MARKERS if marker in text]
        if not hits:
            continue
        guarded = ("try" in text) or ("runCatching" in text) or ("catch" in text)
        report.append(
            f"{path}: {', '.join(hits)} — обработка ошибок: "
            + ("есть" if guarded else "НЕТ")
        )
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--external",
        action="store_true",
        help="проверить обработку ошибок у сетевых/файловых вызовов",
    )
    args = parser.parse_args()

    paths = changed_kotlin_files()
    if not paths:
        print("нет изменённых Kotlin-файлов")
        return 0

    if args.external:
        lines = external_audit(paths)
        print("\n".join(lines) if lines else "внешних вызовов не найдено")
        return 0

    failures = 0
    for path in paths:
        unused = unused_imports(path)
        for name in unused:
            failures += 1
            print(f"{path}: неиспользуемый импорт {name}")
    print(f"проверено файлов: {len(paths)}, неиспользуемых импортов: {failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
