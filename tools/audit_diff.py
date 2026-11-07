#!/usr/bin/env python3
"""Diff audit for Kotlin sources: unused imports and unguarded external calls.

The script is intentionally dependency-free and read-only. It scans files
changed against a base ref (working tree included), checks that every imported
simple name is used outside the import block, and flags network/native call
sites that have no nearby error handling or fallback.

Usage:
    python tools/audit_diff.py [--base HEAD] [--strict]

Exit code is 1 in --strict mode when findings exist.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

IMPORT_RE = re.compile(
    r"^\s*import\s+([A-Za-z_][\w.]*)(?:\s+as\s+([A-Za-z_]\w*))?\s*$",
    re.MULTILINE,
)
EXTERNAL_CALL_RE = re.compile(
    r"(https?://|HttpURLConnection|OkHttpClient|\.newCall\s*\(|"
    r"\bURL\s*\(|\bSocket\s*\(|\bDatagramSocket\s*\(|\bNova\.[A-Za-z_])"
)
GUARD_RE = re.compile(
    r"\b(try|catch|runCatching|getOrElse|getOrNull|onFailure|"
    r"fallback|retry|shouldAbort|protect)\b",
    re.IGNORECASE,
)


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8", errors="replace"
    )


def changed_files(base: str) -> list[Path]:
    names: set[str] = set()
    for args in (
        ("diff", "--name-only", base),
        ("diff", "--name-only", "--cached", base),
        ("ls-files", "--others", "--exclude-standard"),
    ):
        try:
            names.update(line.strip() for line in git(*args).splitlines() if line.strip())
        except subprocess.CalledProcessError:
            continue
    return [
        ROOT / name
        for name in sorted(names)
        if name.startswith(("app/src/main/java/", "app/src/test/java/"))
        and name.endswith(".kt")
    ]


def is_comment(line: str) -> bool:
    stripped = line.lstrip()
    return stripped.startswith(("//", "*", "/*"))


def check_imports(path: Path, text: str) -> list[str]:
    findings: list[str] = []
    imports = IMPORT_RE.findall(text)
    body = IMPORT_RE.sub("", text)
    body = "\n".join(line for line in body.splitlines() if not is_comment(line))
    seen: set[str] = set()
    for full, alias in imports:
        name = alias or full.rsplit(".", 1)[-1]
        if name == "*":
            continue
        key = f"{full} as {alias}" if alias else full
        if key in seen:
            findings.append(f"{path}: duplicate import {key}")
            continue
        seen.add(key)
        if not re.search(rf"\b{re.escape(name)}\b", body):
            findings.append(f"{path}: unused import {key}")
    return findings


def check_external_calls(path: Path, text: str) -> list[str]:
    findings: list[str] = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if is_comment(line) or not EXTERNAL_CALL_RE.search(line):
            continue
        if "audit: guarded" in line:
            continue
        stripped = line.strip()
        if stripped.startswith("import "):
            continue
        # Type positions and client construction are not call sites.
        if re.search(r":\s*OkHttpClient\??", line) and ".newCall" not in line:
            continue
        if stripped.startswith("OkHttpClient.Builder()"):
            continue
        # A bare URL is data (a constant or a list item), not an external call.
        # The call that consumes it is checked at its own line, and helpers such
        # as httpProbe() keep their own error handling.
        if "://" in line and not re.search(
            r"(newCall|HttpURLConnection|OkHttpClient\s*\(|URL\s*\(|"
            r"Socket\s*\(|DatagramSocket\s*\(|Nova\.)",
            line,
        ):
            continue
        start = max(0, index - 10)
        end = min(len(lines), index + 11)
        window = "\n".join(lines[start:end])
        if "audit: guarded" in window:
            continue
        if GUARD_RE.search(window):
            continue
        findings.append(
            f"{path}:{index + 1}: external call without nearby guard/fallback: "
            f"{line.strip()[:120]}"
        )
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="HEAD", help="git ref to diff against")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()

    files = changed_files(args.base)
    if not files:
        print("audit: no changed Kotlin files")
        return 0

    import_findings: list[str] = []
    call_findings: list[str] = []
    for path in files:
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        import_findings += check_imports(path.relative_to(ROOT), text)
        call_findings += check_external_calls(path.relative_to(ROOT), text)

    print(f"audit: files={len(files)} unused_imports={len(import_findings)} "
          f"unguarded_calls={len(call_findings)}")
    for finding in import_findings:
        print("IMPORT", finding)
    for finding in call_findings:
        print("CALL  ", finding)
    if args.strict and (import_findings or call_findings):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
