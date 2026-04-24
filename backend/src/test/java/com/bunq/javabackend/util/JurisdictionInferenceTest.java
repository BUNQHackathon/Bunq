package com.bunq.javabackend.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JurisdictionInferenceTest {

    @Test
    void netherlands_detected() {
        Set<String> result = JurisdictionInference.inferFromText("Can bunq launch crypto card in the Netherlands");
        assertTrue(result.contains("NL"), "Expected NL in: " + result);
    }

    @Test
    void bafin_detects_de() {
        Set<String> result = JurisdictionInference.inferFromText("BaFin requires strict KYC compliance");
        assertTrue(result.contains("DE"), "Expected DE in: " + result);
    }

    @Test
    void uk_fca_detects_uk() {
        Set<String> result = JurisdictionInference.inferFromText("UK FCA rulebook section 3");
        assertTrue(result.contains("UK"), "Expected UK in: " + result);
    }

    @Test
    void empty_string_returns_empty() {
        Set<String> result = JurisdictionInference.inferFromText("");
        assertTrue(result.isEmpty(), "Expected empty set for empty string");
    }

    @Test
    void null_returns_empty() {
        Set<String> result = JurisdictionInference.inferFromText(null);
        assertTrue(result.isEmpty(), "Expected empty set for null");
    }

    @Test
    void us_and_dnb_both_detected() {
        Set<String> result = JurisdictionInference.inferFromText("US and DNB compliance requirements");
        assertTrue(result.contains("US"), "Expected US in: " + result);
        assertTrue(result.contains("NL"), "Expected NL in: " + result);
    }

    @Test
    void no_country_returns_empty() {
        Set<String> result = JurisdictionInference.inferFromText("There's no country here");
        assertTrue(result.isEmpty(), "Expected empty set for unrecognised text, got: " + result);
    }
}
