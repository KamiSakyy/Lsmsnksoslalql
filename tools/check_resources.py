#!/usr/bin/env python3
"""Offline sanity check for the Android resource tree.

It cannot replace aapt2, but it catches the usual causes of a red CI build:
malformed XML, references to resources that do not exist, ids used from Java
that were never declared, and styles whose parent does not exist locally.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path("app/src/main/res")
JAVA = Path("app/src/main/java")

ANDROID = {"android", "http", "https", "schemas"}

problems: list[str] = []


def add(message: str) -> None:
    problems.append(message)


def parse(path: Path) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as error:
        add(f"{path}: XML parse error: {error}")
        return None


def collect_definitions() -> dict[str, set[str]]:
    defined: dict[str, set[str]] = {kind: set() for kind in
        ("color", "dimen", "style", "string", "plurals", "bool", "integer", "array")}

    for path in RES.glob("values*/*.xml"):
        root = parse(path)
        if root is None:
            continue
        for child in root:
            if not isinstance(child.tag, str):
                continue
            name = child.get("name")
            if not name:
                continue
            tag = child.tag
            if tag in defined:
                defined[tag].add(name)
            elif tag == "item":
                item_type = child.get("type")
                if item_type in defined:
                    defined[item_type].add(name)

    for kind, folder in (("layout", "layout"), ("drawable", "drawable"),
                         ("anim", "anim"), ("menu", "menu"), ("xml", "xml"),
                         ("color", None)):
        if folder is None:
            continue
        defined[kind] = {p.stem for p in RES.glob(f"{folder}*/**/*") if p.is_file()}

    defined["color"] |= {p.stem for p in RES.glob("color*/*") if p.is_file()}

    ids: set[str] = set()
    for folder in ("layout", "menu", "navigation", "drawable", "anim", "xml"):
        for path in RES.glob(f"{folder}*/*.xml"):
            text = path.read_text(encoding="utf-8")
            ids.update(re.findall(r"@\+id/([A-Za-z0-9_]+)", text))
    defined["id"] = ids
    return defined


REFERENCE = re.compile(r"@(\+?)([A-Za-z0-9_]+)/([A-Za-z0-9_.]+)")


def check_references(defined: dict[str, set[str]]) -> None:
    files = [p for p in RES.glob("**/*.xml") if p.is_file()]
    files += [p for p in JAVA.glob("**/*.java")]
    for path in files:
        text = path.read_text(encoding="utf-8")
        for plus, kind, name in REFERENCE.findall(text):
            if kind in ANDROID:
                continue
            if kind == "style" and name.startswith("Theme.Tsuyu") and path.suffix == ".java":
                pass
            pool = defined.get(kind)
            if pool is None:
                continue
            if name not in pool:
                add(f"{path}: @{kind}/{name} is not defined")
            elif plus == "+" and kind == "id" and name not in defined["id"]:
                add(f"{path}: id/@{name} declared but missing")


def check_local_style_parents(defined: dict[str, set[str]]) -> None:
    local = defined["style"]
    for path in RES.glob("values*/*.xml"):
        root = parse(path)
        if root is None:
            continue
        for style in root.iter("style"):
            parent = style.get("parent")
            name = style.get("name")
            if parent and parent in local and parent == name:
                add(f"{path}: style {name} is its own parent")
            if parent and parent.startswith(("Widget.Tsuyu", "TextAppearance.Tsuyu", "Tsuyu")):
                if parent not in local:
                    add(f"{path}: style {name} references missing parent {parent}")


def check_java_references(defined: dict[str, set[str]]) -> None:
    pattern = re.compile(r"(?<!android\.)R\.([a-z]+)\.([A-Za-z0-9_]+)")
    for path in JAVA.glob("**/*.java"):
        text = path.read_text(encoding="utf-8")
        for kind, name in pattern.findall(text):
            pool = defined.get(kind)
            if pool is None:
                continue
            if name not in pool:
                add(f"{path}: R.{kind}.{name} is not defined in resources")


def main() -> int:
    defined = collect_definitions()
    check_references(defined)
    check_local_style_parents(defined)
    check_java_references(defined)
    print(f"layouts: {len(defined['layout'])}, drawables: {len(defined['drawable'])}, "
          f"styles: {len(defined['style'])}, colors: {len(defined['color'])}, "
          f"dimens: {len(defined['dimen'])}, ids: {len(defined['id'])}")
    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(" -", problem)
        return 1
    print("no resource problems found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
