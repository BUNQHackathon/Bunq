"""
Name normalization utilities shared by normalize_sanctions.py and tests.
Importable without any FastAPI / sidecar dependencies.
"""
import re
import unicodedata

# Legal suffixes to strip (order matters: longer first to avoid partial matches)
_LEGAL_SUFFIXES = [
    "s.a.r.l",
    "s.a",
    "gmbh",
    "corp",
    "limited",
    "llc",
    "plc",
    "inc",
    "ltd",
    "ag",
    "co",
]

# Pre-compiled pattern: strip all punctuation except spaces
_PUNCT_RE = re.compile(r"[^\w\s]", re.UNICODE)
_SPACE_RE = re.compile(r"\s+")

# Suffix pattern built from the list (word-boundary aware)
_SUFFIX_PATTERN = re.compile(
    r"\b(" + "|".join(re.escape(s) for s in _LEGAL_SUFFIXES) + r")\b",
    re.IGNORECASE,
)


def normalize_name(name: str) -> str:
    """
    Lowercase, remove legal suffixes, strip punctuation, collapse whitespace.

    Order:
      1. NFC unicode + lowercase
      2. Remove legal suffixes FIRST (before punct strip, so "S.A.R.L" matches)
      3. Strip remaining punctuation
      4. Collapse whitespace

    Examples:
        normalize_name("Zeta GmbH")    -> "zeta"
        normalize_name("Acme, LLC")    -> "acme"
        normalize_name("Dupont S.A.R.L") -> "dupont"
        normalize_name("John DOE")     -> "john doe"
    """
    if not name:
        return ""

    # 1. Unicode normalization (NFC) then lower
    text = unicodedata.normalize("NFC", name).lower()

    # 2. Remove legal suffixes first (iterate until stable — handles "Inc. Ltd.")
    prev = None
    while prev != text:
        prev = text
        text = _SUFFIX_PATTERN.sub(" ", text)

    # 3. Strip punctuation
    text = _PUNCT_RE.sub(" ", text)

    # 4. Collapse whitespace and strip edges
    text = _SPACE_RE.sub(" ", text).strip()

    return text
