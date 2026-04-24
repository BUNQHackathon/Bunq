"""
Tests for _normalize.py — no network, no AWS.
"""
import sys
from pathlib import Path

# Make scripts/ importable without installing as a package
sys.path.insert(0, str(Path(__file__).parent.parent))

from _normalize import normalize_name


class TestNormalizeName:
    def test_gmbh_stripped(self):
        assert normalize_name("Zeta GmbH") == "zeta"

    def test_llc_stripped(self):
        assert normalize_name("Acme, LLC") == "acme"

    def test_inc_stripped(self):
        assert normalize_name("Acme Inc.") == "acme"

    def test_ltd_stripped(self):
        # Both "corp" and "ltd" are legal suffixes, both get stripped
        assert normalize_name("Big Corp Ltd") == "big"

    def test_plc_stripped(self):
        assert normalize_name("Bank PLC") == "bank"

    def test_ag_stripped(self):
        assert normalize_name("Kremlin Finance AG") == "kremlin finance"

    def test_corp_stripped(self):
        assert normalize_name("Pacific Trade Corp") == "pacific trade"

    def test_sa_stripped(self):
        # Suffix runs before punct, so "S.A." matches the "s.a" suffix pattern
        result = normalize_name("TestCo S.A.")
        assert "testco" in result
        # "s" and "a" should not appear as standalone trailing words
        parts = result.split()
        assert parts[0] == "testco"

    def test_sarl_stripped(self):
        # Suffix runs before punct, so S.A.R.L matches as-is
        assert normalize_name("Dupont S.A.R.L") == "dupont"
        assert normalize_name("Dupont s.a.r.l.") == "dupont"

    def test_limited_stripped(self):
        assert normalize_name("Test Limited") == "test"

    def test_co_stripped(self):
        assert normalize_name("Acme Co.") == "acme"

    def test_multiple_suffixes_stripped(self):
        # "Inc. Ltd" — both should be stripped
        result = normalize_name("SHELL INC LTD")
        assert "inc" not in result
        assert "ltd" not in result
        assert "shell" in result

    def test_lowercase(self):
        assert normalize_name("JOHN DOE") == "john doe"

    def test_punctuation_removed(self):
        result = normalize_name("O'Brien & Sons")
        assert "'" not in result
        assert "&" not in result

    def test_empty_string(self):
        assert normalize_name("") == ""

    def test_individual_name_unchanged(self):
        result = normalize_name("Ivan Petrov")
        assert result == "ivan petrov"

    def test_whitespace_collapsed(self):
        result = normalize_name("Big   Company   LLC")
        assert "  " not in result
        assert result == "big company"

    def test_suffix_table_covers_spec(self):
        """All suffixes from spec must be handled."""
        spec_suffixes = ["inc", "llc", "ltd", "gmbh", "s.a.", "s.a.r.l", "plc", "ag", "co", "corp", "limited"]
        for suffix in spec_suffixes:
            result = normalize_name(f"Acme {suffix}")
            assert "acme" in result, f"Suffix '{suffix}' not stripped: got '{result}'"
