"""
Tests for OFAC SDN parsing — uses fixture CSVs, no network, no AWS.
"""
import json
import sys
from pathlib import Path

import pytest

SCRIPTS_DIR = Path(__file__).parent.parent
FIXTURE_DIR = SCRIPTS_DIR / "fixtures" / "ofac_sample"

sys.path.insert(0, str(SCRIPTS_DIR))

from normalize_sanctions import _parse_ofac_sdn


REQUIRED_KEYS = {
    "list_source",
    "entity_name",
    "entity_name_normalized",
    "aliases",
    "country",
    "type",
    "list_entry_id",
    "list_version_timestamp",
}


@pytest.fixture
def parsed_entities():
    """Parse fixture CSVs and return list of entity dicts."""
    entities = list(_parse_ofac_sdn(input_dir=FIXTURE_DIR))
    return entities


class TestOfacParse:
    def test_min_rows(self, parsed_entities):
        """Parser must produce at least 5 entities from fixture."""
        assert len(parsed_entities) >= 5

    def test_required_keys_present(self, parsed_entities):
        """Every entity must have all required keys."""
        for entity in parsed_entities:
            missing = REQUIRED_KEYS - entity.keys()
            assert not missing, f"Entity missing keys {missing}: {entity}"

    def test_id_format(self, parsed_entities):
        """list_entry_id must follow sdn-<ent_num> pattern."""
        import re
        for entity in parsed_entities:
            assert re.match(r"^sdn-\d+$", entity["list_entry_id"]), (
                f"Bad list_entry_id: {entity['list_entry_id']}"
            )

    def test_list_source(self, parsed_entities):
        """list_source must be OFAC_SDN for all entities."""
        for entity in parsed_entities:
            assert entity["list_source"] == "OFAC_SDN"

    def test_type_values(self, parsed_entities):
        """type must be one of the canonical values."""
        valid_types = {"individual", "company", "organization", "government", "unknown"}
        for entity in parsed_entities:
            assert entity["type"] in valid_types, (
                f"Invalid type '{entity['type']}' for {entity['entity_name']}"
            )

    def test_zeta_gmbh_present(self, parsed_entities):
        """Fixture contains ZETA GMBH — must appear in output."""
        names = [e["entity_name"] for e in parsed_entities]
        assert any("ZETA" in n for n in names), f"ZETA not found in {names}"

    def test_zeta_gmbh_normalized(self, parsed_entities):
        """ZETA GMBH should normalize to 'zeta' (gmbh stripped)."""
        zeta = next(
            (e for e in parsed_entities if "ZETA" in e["entity_name"]),
            None,
        )
        assert zeta is not None
        assert zeta["entity_name_normalized"] == "zeta", (
            f"Expected 'zeta', got '{zeta['entity_name_normalized']}'"
        )

    def test_zeta_gmbh_country(self, parsed_entities):
        """ZETA GMBH fixture address is Kazakhstan (KZ)."""
        zeta = next(
            (e for e in parsed_entities if "ZETA" in e["entity_name"]),
            None,
        )
        assert zeta is not None
        assert zeta["country"] == "KZ", f"Expected KZ, got {zeta['country']}"

    def test_entity_type_mapping(self, parsed_entities):
        """entity-type SDN entries must map to 'company'."""
        entities = [e for e in parsed_entities if "TRADING" in e["entity_name"] or "GMBH" in e["entity_name"]]
        for e in entities:
            assert e["type"] == "company", (
                f"Expected 'company' for {e['entity_name']}, got '{e['type']}'"
            )

    def test_individual_type_mapping(self, parsed_entities):
        """individual SDN entries must map to 'individual'."""
        individuals = [e for e in parsed_entities if "PETROV" in e["entity_name"] or "KHAN" in e["entity_name"]]
        for e in individuals:
            assert e["type"] == "individual", (
                f"Expected 'individual' for {e['entity_name']}, got '{e['type']}'"
            )

    def test_vessel_type_mapping(self, parsed_entities):
        """vessel SDN entries must map to 'organization'."""
        vessels = [e for e in parsed_entities if "SHIPPING" in e["entity_name"]]
        for e in vessels:
            assert e["type"] == "organization", (
                f"Expected 'organization' for {e['entity_name']}, got '{e['type']}'"
            )

    def test_aliases_populated(self, parsed_entities):
        """At least some entities should have aliases."""
        entities_with_aliases = [e for e in parsed_entities if e["aliases"]]
        assert len(entities_with_aliases) >= 1

    def test_dynamo_id_format(self, parsed_entities):
        """DynamoDB id = list_source#list_entry_id must be derivable."""
        for entity in parsed_entities:
            dynamo_id = f"{entity['list_source']}#{entity['list_entry_id']}"
            assert dynamo_id.startswith("OFAC_SDN#sdn-")

    def test_jsonl_serializable(self, parsed_entities):
        """All entities must be JSON-serializable."""
        for entity in parsed_entities:
            serialized = json.dumps(entity)
            roundtripped = json.loads(serialized)
            assert roundtripped["list_source"] == entity["list_source"]
