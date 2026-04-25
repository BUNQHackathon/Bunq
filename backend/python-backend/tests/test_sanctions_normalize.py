import pytest
from app.services.sanctions_screener import normalize_name


def test_gmbh_stripped():
    assert normalize_name("Zeta GmbH") == "zeta"


def test_llc_stripped():
    assert normalize_name("Acme, LLC") == "acme"


def test_inc_stripped():
    assert normalize_name("Global Inc") == "global"


def test_ltd_stripped():
    assert normalize_name("Omega Ltd") == "omega"


def test_plc_stripped():
    assert normalize_name("Foobar PLC") == "foobar"


def test_multiple_words():
    assert normalize_name("Acme Corp International") == "acme international"


def test_already_normalized():
    assert normalize_name("john doe") == "john doe"


def test_punctuation_removed():
    assert normalize_name("Smith & Jones, Co.") == "smith jones"
