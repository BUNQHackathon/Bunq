package com.bunq.javabackend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/jurisdictions")
public class JurisdictionsController {

    public record Jurisdiction(
        String code,
        String name,
        String flag,
        String status,
        String license,
        String regulator
    ) {}

    private static final List<Jurisdiction> ALL = List.of(
        new Jurisdiction("NLD", "Netherlands", "🇳🇱", "active", "Full Banking License", "De Nederlandsche Bank (DNB)"),
        new Jurisdiction("DEU", "Germany", "🇩🇪", "active", "EU Passport (DNB)", "BaFin"),
        new Jurisdiction("FRA", "France", "🇫🇷", "active", "EU Passport (DNB)", "ACPR"),
        new Jurisdiction("ESP", "Spain", "🇪🇸", "active", "EU Passport (DNB)", "Banco de España"),
        new Jurisdiction("ITA", "Italy", "🇮🇹", "active", "EU Passport (DNB)", "Banca d'Italia"),
        new Jurisdiction("IRL", "Ireland", "🇮🇪", "active", "EU Passport (DNB)", "Central Bank of Ireland"),
        new Jurisdiction("BEL", "Belgium", "🇧🇪", "active", "EU Passport (DNB)", "NBB"),
        new Jurisdiction("LUX", "Luxembourg", "🇱🇺", "active", "EU Passport (DNB)", "CSSF"),
        new Jurisdiction("AUT", "Austria", "🇦🇹", "active", "EU Passport (DNB)", "FMA"),
        new Jurisdiction("POL", "Poland", "🇵🇱", "active", "EU Passport (DNB)", "KNF"),
        new Jurisdiction("NOR", "Norway", "🇳🇴", "active", "EEA Passport (DNB)", "Finanstilsynet"),
        new Jurisdiction("SWE", "Sweden", "🇸🇪", "active", "EEA Passport (DNB)", "Finansinspektionen"),
        new Jurisdiction("DNK", "Denmark", "🇩🇰", "active", "EEA Passport (DNB)", "Finanstilsynet"),
        new Jurisdiction("GBR", "United Kingdom", "🇬🇧", "active", "E-Money Institution", "FCA"),
        new Jurisdiction("CHE", "Switzerland", "🇨🇭", "active", "FINMA Authorized", "FINMA"),
        new Jurisdiction("USA", "United States", "🇺🇸", "watchlist", "Expansion Review", "FinCEN / OCC"),
        new Jurisdiction("TUR", "Turkey", "🇹🇷", "watchlist", "Expansion Review", "BDDK"),
        new Jurisdiction("SGP", "Singapore", "🇸🇬", "watchlist", "Expansion Review", "MAS"),
        new Jurisdiction("ARE", "United Arab Emirates", "🇦🇪", "watchlist", "Expansion Review", "CBUAE"),
        new Jurisdiction("SAU", "Saudi Arabia", "🇸🇦", "watchlist", "Expansion Review", "SAMA"),
        new Jurisdiction("RUS", "Russia", "🇷🇺", "restricted", "Restricted", "N/A"),
        new Jurisdiction("BLR", "Belarus", "🇧🇾", "restricted", "Restricted", "N/A"),
        new Jurisdiction("IRN", "Iran", "🇮🇷", "restricted", "Restricted", "N/A"),
        new Jurisdiction("PRK", "North Korea", "🇰🇵", "restricted", "Restricted", "N/A"),
        new Jurisdiction("SYR", "Syria", "🇸🇾", "restricted", "Restricted", "N/A"),
        new Jurisdiction("CUB", "Cuba", "🇨🇺", "restricted", "Restricted", "N/A")
    );

    @GetMapping
    public List<Jurisdiction> list() {
        return ALL;
    }
}
