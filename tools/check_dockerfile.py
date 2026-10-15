#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Самопроверка Dockerfile перед пушем в Railway 2026.

Что проверяется:
  1. Нет инструкций, которые Railway отклоняет (VOLUME, HEALTHCHECK, ONBUILD, STOPSIGNAL).
  2. Все источники COPY существуют и не вырезаны .dockerignore.
  3. Shell-синтаксис каждой строки RUN (sh -n, без выполнения).
  4. Известные «безопасные» инструкции и есть CMD.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # корень репозитория (Dockerfile лежит там)
FAIL = []
OK = []

FORBIDDEN = {
    "VOLUME": "Railway: 'docker VOLUME is not supported, use Railway Volumes'",
    "HEALTHCHECK": "Railway не использует HEALTHCHECK (риск валидации + дублирует свой healthcheck)",
    "ONBUILD": "Railway отклоняет ONBUILD",
    "STOPSIGNAL": "не нужен и не поддерживается",
}
KNOWN = {"FROM", "ENV", "RUN", "COPY", "ADD", "WORKDIR", "CMD", "ENTRYPOINT", "ARG",
         "LABEL", "USER", "EXPOSE", "SHELL", "MAINTAINER"}

text = (ROOT / "Dockerfile").read_text(encoding="utf-8")

# --- 1. собираем инструкции (минуя комментарии и продолжения строк) ---------
logical: list[tuple[int, str]] = []
buffer, start = "", 0
for number, line in enumerate(text.splitlines(), start=1):
    if not buffer:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        start = number
    buffer += line.rstrip("\\") + (" " if line.endswith("\\") else "\n")
    if not line.rstrip().endswith("\\"):
        logical.append((start, buffer.strip()))
        buffer = ""

instructions = [(n, cmd.split(None, 1)[0].upper(), cmd) for n, cmd in logical]
kinds = [kind for _n, kind, _c in instructions]

for number, kind, cmd in instructions:
    if kind in FORBIDDEN:
        FAIL.append(f"строка {number}: запрещённая Railway инструкция {kind} — {FORBIDDEN[kind]}")
    elif kind not in KNOWN:
        FAIL.append(f"строка {number}: неизвестная инструкция {kind}")
if "CMD" not in kinds and "ENTRYPOINT" not in kinds:
    FAIL.append("нет CMD/ENTRYPOINT — контейнер не запустится")
if "FROM" not in kinds:
    FAIL.append("нет FROM")
if not FAIL:
    OK.append(f"инструкции валидны: {', '.join(kinds)}")

# --- 2. COPY: источники существуют и не вырезаны .dockerignore --------------
ignore = ROOT / ".dockerignore"
patterns = []
if ignore.exists():
    for raw in ignore.read_text(encoding="utf-8").splitlines():
        raw = raw.strip()
        if raw and not raw.startswith("#"):
            patterns.append(raw)


def ignored(path: str) -> str | None:
    """Грубая, но полезная эмуляция правил .dockerignore (with ! ре-инклюдами)."""
    ignored_by: str | None = None
    for pattern in patterns:
        negate = pattern.startswith("!")
        rule = pattern[1:] if negate else pattern
        hit = (
            Path(path).match(rule)
            or Path(path).name == rule
            or any(Path(path).match(part + "/**") for part in [rule.rstrip("/")])
        )
        if hit:
            ignored_by = None if negate else pattern
    return ignored_by


copy_sources: list[str] = []
for _number, kind, cmd in instructions:
    if kind in ("COPY", "ADD"):
        args = [a for a in cmd.split()[1:] if not a.startswith("--")]
        copy_sources.extend(args[:-1])

for source in copy_sources:
    if source == ".":
        continue
    path = ROOT / source
    if not path.exists():
        FAIL.append(f"COPY {source}: файла нет в репозитории")
        continue
    hit = ignored(source)
    if hit:
        FAIL.append(f"COPY {source}: вырезан правилом .dockerignore «{hit}» — сборка упадёт!")
    else:
        OK.append(f"COPY {source}: файл на месте и не вырезан .dockerignore")

# --- 3. shell-синтаксис строк RUN ------------------------------------------
for number, kind, cmd in instructions:
    if kind != "RUN":
        continue
    script = cmd[3:].strip()
    check = subprocess.run(["/bin/sh", "-n"], input=script, text=True, capture_output=True)
    if check.returncode != 0:
        FAIL.append(f"строка {number}: синтаксис RUN сломан: {check.stderr.strip()[:200]}")
    else:
        OK.append(f"строка {number}: RUN — shell-синтаксис ok")

# --- 4. отчёт --------------------------------------------------------------
for line in OK:
    print("  ✓", line)
for line in FAIL:
    print("  ✗", line, file=sys.stderr)
print()
print(f"ИТОГ: {len(OK)} проверок пройдено, {len(FAIL)} проблем")
sys.exit(1 if FAIL else 0)
