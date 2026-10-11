"""WSGI entry point for the legacy single-file Tsuyu application."""
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

_source = Path(__file__).with_name("server (13).py")
_spec = spec_from_file_location("tsuyu_server", _source)
if _spec is None or _spec.loader is None:
    raise RuntimeError(f"Cannot load application from {_source}")

_module = module_from_spec(_spec)
_spec.loader.exec_module(_module)
app = _module.app
