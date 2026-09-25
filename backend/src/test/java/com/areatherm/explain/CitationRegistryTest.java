package com.areatherm.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * No live Ollama needed -- CitationRegistry is pure text processing. Covers
 * the anti-hallucination scan this feature leans on: real generated text
 * from a live model will vary run to run, but the registry's own logic
 * (what counts as "cited", what counts as "an unrecognized citation") must
 * be deterministic and correct regardless.
 */
class CitationRegistryTest {

    @Test
    void promptTextListsEveryRegisteredCitation() {
        String text = CitationRegistry.allAsPromptText();
        for (CitationRegistry.Citation c : CitationRegistry.ALL) {
            assertTrue(text.contains(c.matchToken()), "Prompt text should mention " + c.matchToken());
        }
    }

    @Test
    void detectsAKnownCitationAndReportsGoodHygiene() {
        String text = "This design benefits from good insulation. Per ASHRAE 55, comfort depends on more than temperature alone.";
        CitationRegistry.ScanResult result = CitationRegistry.scan(text);
        assertTrue(result.usedCodes().contains("ASHRAE_55"));
        assertTrue(result.hygieneOk());
    }

    @Test
    void detectsMultipleKnownCitations() {
        String text = "See ISO 8996 for the metabolic-rate basis, and ASHRAE Fundamentals Chapter 9 for the wattage figures.";
        CitationRegistry.ScanResult result = CitationRegistry.scan(text);
        assertTrue(result.usedCodes().contains("ISO_8996"));
        assertTrue(result.usedCodes().contains("ASHRAE_FUND_CH9"));
        assertTrue(result.hygieneOk());
    }

    @Test
    void flagsAnUnrecognizedCitationAsPoorHygiene() {
        // ASHRAE 90.1 is deliberately NOT in the registry (see CitationRegistry's javadoc) --
        // a model citing it anyway must be caught, not silently trusted.
        String text = "This design should also comply with ASHRAE 90.1 for envelope performance.";
        CitationRegistry.ScanResult result = CitationRegistry.scan(text);
        assertFalse(result.hygieneOk());
    }

    @Test
    void plainExplanationWithNoCitationsIsClean() {
        String text = "The design retains heat well because of its thick insulated walls and south-facing windows.";
        CitationRegistry.ScanResult result = CitationRegistry.scan(text);
        assertEquals(0, result.usedCodes().size());
        assertTrue(result.hygieneOk());
    }

    @Test
    void mentioningPassiveHouseDoesNotTriggerTheNumericCitationScan() {
        String text = "This follows Passive House Institute guidance on airtightness.";
        CitationRegistry.ScanResult result = CitationRegistry.scan(text);
        assertTrue(result.usedCodes().contains("PHI"));
        assertTrue(result.hygieneOk());
    }

    @Test
    void nullAndBlankTextAreHandledSafely() {
        assertTrue(CitationRegistry.scan(null).hygieneOk());
        assertTrue(CitationRegistry.scan("").hygieneOk());
        assertEquals(0, CitationRegistry.scan(null).usedCodes().size());
    }
}
