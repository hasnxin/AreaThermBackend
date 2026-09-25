package com.areatherm.explain;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixed, hand-verified table of references the design-explanation LLM is
 * allowed to cite -- deliberately not sourced from the model itself, and
 * deliberately NOT the fictional catalog-style citation list that shows up
 * in some AI-integration proposals. Each entry is included only because
 * something this engine actually computes is genuinely traceable to it (see
 * each entry's {@code topic}) -- e.g. ASHRAE 90.1 (a prescriptive
 * building-energy-code standard) is deliberately excluded, because nothing
 * here performs a code-compliance check against it, and citing it would
 * imply a compliance claim this app doesn't make.
 *
 * Used two ways: (1) {@link #allAsPromptText()} is handed to the model
 * verbatim as the *only* citations it's allowed to use, so it never has to
 * rely on (and risk misremembering) whatever it learned about these
 * standards during training; (2) {@link #scan(String)} independently
 * re-checks the model's raw output afterward, rather than trusting the
 * model's own compliance with instruction (1) alone.
 */
public final class CitationRegistry {

    public record Citation(String code, String title, String matchToken, String topic) {
    }

    public static final List<Citation> ALL = List.of(
        new Citation(
            "ASHRAE_55",
            "ASHRAE 55 — Thermal Environmental Conditions for Human Occupancy",
            "ASHRAE 55",
            "Defines widely-used thermal comfort criteria (temperature/humidity/air-speed ranges). "
                + "General context only -- this app's own Thermal Comfort Score is a distinct, custom-weighted "
                + "index, not a PMV/PPD calculation performed under this standard."
        ),
        new Citation(
            "ASHRAE_FUND_CH9",
            "ASHRAE Fundamentals Handbook, Chapter 9 (Thermal Comfort)",
            "ASHRAE Fundamentals",
            "Source of the order-of-magnitude occupant sensible/latent heat-output figures by activity "
                + "level used directly in this app's occupancy model."
        ),
        new Citation(
            "ISO_8996",
            "ISO 8996 — Ergonomics of the thermal environment: Determination of metabolic rate",
            "ISO 8996",
            "Source of the metabolic-rate figures underlying the same occupancy heat model."
        ),
        new Citation(
            "PHI",
            "Passive House Institute (PHI) design guidance",
            "Passive House",
            "General passive-building design principles (insulation levels, airtightness, solar "
                + "orientation, thermal-bridge-free construction). This app's specific formulas are an "
                + "independent RC-network model, not a PHI certification methodology."
        )
    );

    // Loose citation-shaped token scan (e.g. "ASHRAE 55", "ISO 8996", "IS 875")
    // used to flag an UNRECOGNIZED citation the model produced on its own
    // despite being told to only cite from ALL above -- defense in depth,
    // not a claim of perfectly catching every possible fabrication.
    private static final Pattern CITATION_LIKE = Pattern.compile(
        "\\b(ASHRAE|ISO|IS)\\s*[- ]?\\d+(\\.\\d+)?\\b", Pattern.CASE_INSENSITIVE);

    private CitationRegistry() {
    }

    public static String allAsPromptText() {
        StringBuilder sb = new StringBuilder();
        for (Citation c : ALL) {
            sb.append("- [").append(c.matchToken()).append("] ").append(c.title())
                .append(" -- ").append(c.topic()).append('\n');
        }
        return sb.toString();
    }

    public record ScanResult(List<String> usedCodes, boolean hygieneOk) {
    }

    public static ScanResult scan(String text) {
        if (text == null || text.isBlank()) return new ScanResult(List.of(), true);
        String upper = text.toUpperCase();

        List<String> used = ALL.stream()
            .filter(c -> upper.contains(c.matchToken().toUpperCase()))
            .map(Citation::code)
            .toList();

        boolean hygieneOk = true;
        Matcher matcher = CITATION_LIKE.matcher(text);
        while (matcher.find()) {
            String found = matcher.group().replaceAll("\\s+", " ").trim().toUpperCase();
            boolean known = ALL.stream().anyMatch(c -> found.startsWith(c.matchToken().toUpperCase()));
            if (!known) {
                hygieneOk = false;
                break;
            }
        }
        return new ScanResult(used, hygieneOk);
    }
}
