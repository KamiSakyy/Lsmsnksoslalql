# -*- coding: utf-8 -*-
"""Прогон всех тестов бота: python tests/run_all.py"""
from __future__ import annotations

import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
tests = sorted(p.name for p in ROOT.glob("test_*.py"))
failed: list[str] = []
for name in tests:
    print(f"\n{'=' * 70}\n▶ {name}\n{'=' * 70}")
    result = subprocess.run([sys.executable, str(ROOT / name)], cwd=str(ROOT.parent))
    if result.returncode != 0:
        failed.append(name)
print(f"\n{'=' * 70}")
if failed:
    print(f"❌ УПАЛИ: {', '.join(failed)}")
    sys.exit(1)
print(f"✅ ВСЕ ТЕСТЫ ПРОШЛИ ({len(tests)}: {', '.join(tests)})")
