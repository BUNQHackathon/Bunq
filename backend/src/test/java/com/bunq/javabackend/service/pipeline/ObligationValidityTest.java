package com.bunq.javabackend.service.pipeline;

import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.obligation.ObligationSource;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObligationValidityTest {

    private static Obligation obligation(String subject, String action) {
        return Obligation.builder().subject(subject).action(action).build();
    }

    // --- DROPS ---

    @Test
    void dropsBlankAction() {
        Obligation o = obligation("obliged entities", "   ");
        assertEquals(Optional.of(ObligationValidity.DropReason.EMPTY), ObligationValidity.check(o));
    }

    @Test
    void dropsBlankSubject() {
        Obligation o = obligation("", "report suspicious transactions");
        assertEquals(Optional.of(ObligationValidity.DropReason.EMPTY), ObligationValidity.check(o));
    }

    @Test
    void dropsSubjectCustomers() {
        Obligation o = obligation("Customers", "must provide identification data upon request");
        assertEquals(Optional.of(ObligationValidity.DropReason.WRONG_PARTY), ObligationValidity.check(o));
    }

    @Test
    void dropsSubjectTheAdministration() {
        Obligation o = obligation("The administration", "shall maintain a public register");
        assertEquals(Optional.of(ObligationValidity.DropReason.WRONG_PARTY), ObligationValidity.check(o));
    }

    @Test
    void dropsSubjectSelfRegulatoryOrganizations() {
        Obligation o = obligation("Self-regulatory organizations", "shall issue guidance to members");
        assertEquals(Optional.of(ObligationValidity.DropReason.WRONG_PARTY), ObligationValidity.check(o));
    }

    @Test
    void dropsSubjectComitatoSicurezzaFinanziaria() {
        Obligation o = obligation("Comitato di sicurezza finanziaria", "shall update the list of designated persons");
        assertEquals(Optional.of(ObligationValidity.DropReason.WRONG_PARTY), ObligationValidity.check(o));
    }

    @Test
    void dropsSubjectGuardiaDiFinanza() {
        Obligation o = obligation("Guardia di finanza", "shall carry out inspections");
        assertEquals(Optional.of(ObligationValidity.DropReason.WRONG_PARTY), ObligationValidity.check(o));
    }

    @Test
    void dropsSubjectUifGuardiaDiFinanzaAndDia() {
        Obligation o = obligation("UIF, Guardia di finanza and DIA", "shall exchange information");
        assertEquals(Optional.of(ObligationValidity.DropReason.WRONG_PARTY), ObligationValidity.check(o));
    }

    @Test
    void dropsActionMentioningEditFunction() {
        Obligation o = obligation("obliged entities", "correct the record using the 'Edit' function");
        assertEquals(Optional.of(ObligationValidity.DropReason.TOOL_MANUAL), ObligationValidity.check(o));
    }

    @Test
    void dropsActionMentioningImportedData() {
        Obligation o = obligation("obliged entities", "verify the imported data before submission");
        assertEquals(Optional.of(ObligationValidity.DropReason.TOOL_MANUAL), ObligationValidity.check(o));
    }

    // --- KEEPS (regression guards) ---

    @Test
    void keepsObligedEntitiesWithNormalAction() {
        Obligation o = obligation("obliged entities", "report suspicious transactions to the UIF within 30 days");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsBankingAndFinancialIntermediaries() {
        Obligation o = obligation("banking and financial intermediaries (intermediari bancari e finanziari)",
                "verify customer identity before establishing a business relationship");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsObligedEntitiesEvenWhenActionMentionsThirdParty() {
        // Proves we decide by subject, not by third parties mentioned in the action.
        Obligation o = obligation("obliged entities",
                "verify that cash transport companies can provide identification data");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsAmlFunction() {
        Obligation o = obligation("AML function", "conduct enhanced due diligence on high-risk customers");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsSegnalantiObligedReporters() {
        Obligation o = obligation("segnalanti (obliged reporters)", "submit suspicious activity reports promptly");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsWhenSourceIsNotToolManual() {
        Obligation o = obligation("obliged entities", "maintain records for at least ten years");
        ObligationSource source = new ObligationSource();
        source.setRegulation("D.Lgs. 231/2007");
        o.setSource(source);
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    // --- KEEPS (regression guards: bare-substring collisions on short acronyms/words,
    // e.g. DENY "dia" matching mid-word inside "custodians") ---

    @Test
    void keepsCustodians() {
        Obligation o = obligation("custodians", "safeguard client assets held in custody");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsCustodialInstitutions() {
        Obligation o = obligation("custodial institutions", "report material discrepancies to the AML function");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsGuardiansOfClientAssets() {
        // "client" only appears in the trailing qualifier ("of client assets") — the obligated
        // party (subject HEAD) is "guardians", so this must not drop.
        Obligation o = obligation("guardians of client assets", "segregate assets from own-account holdings");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsInsuranceIntermediaries() {
        Obligation o = obligation("insurance intermediaries", "verify beneficial ownership before onboarding");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsTrustAndCompanyServiceProviders() {
        Obligation o = obligation("trust and company service providers", "identify the ultimate beneficial owner");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsCryptoAssetServiceProviders() {
        Obligation o = obligation("crypto-asset service providers", "screen transactions against sanctions lists");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsCreditInstitutions() {
        Obligation o = obligation("credit institutions", "assess customer risk at onboarding");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    // --- KEEPS (regression guards: short acronyms as prefixes of ordinary words,
    // e.g. DENY "dia" matching "Dia(mond)") ---

    @Test
    void keepsDiamondDealers() {
        Obligation o = obligation("Diamond dealers", "verify the origin of high-value gemstones");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsDealersInPreciousStones() {
        Obligation o = obligation("dealers in precious stones and metals",
                "report cash transactions above the threshold");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void keepsUniformRequirements() {
        Obligation o = obligation("uniform reporting requirements", "apply consistent AML controls group-wide");
        assertTrue(ObligationValidity.check(o).isEmpty());
    }

    @Test
    void dropsAuthoritiesAcronyms() {
        // Regression guard for the acronym whole-word rule: "uif"/"dia" must still match as
        // standalone acronyms even though they no longer match as bare prefixes.
        Obligation o = obligation("UIF, Guardia di finanza and DIA", "shall exchange information");
        assertEquals(Optional.of(ObligationValidity.DropReason.WRONG_PARTY), ObligationValidity.check(o));
    }
}
