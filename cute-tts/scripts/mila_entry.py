"""Точка входа для PyInstaller-сборки."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cute_tts.app import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
