"""
pytest configuration for scripts tests.
Adds the scripts/ directory to sys.path so _normalize, normalize_sanctions,
and parse_nist_controls are importable without installation.
"""
import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).parent.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))
