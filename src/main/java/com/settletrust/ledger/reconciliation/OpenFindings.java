package com.settletrust.ledger.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What is wrong with the books right now, as opposed to what some run once said.
 *
 * <p>Every report stored in {@code reconciliation_run} answers "what did that pass find",
 * which is the right question for evidence and the wrong one during an incident. Once
 * runs became incremental the difference stopped being cosmetic: a windowed run reports a
 * fault the first time it sees it and then moves past it, so a fault nobody fixed does not
 * appear in later reports and "the last run was clean" stops meaning the book is.
 *
 * <p><b>Only a full run can close a finding.</b> An incremental run looked at a window,
 * so its silence about a subject means it did not look, not that the subject is well. So
 * the open set is anchored on the most recent {@link RunMode#FULL} run: everything that
 * run found, plus everything found by any run after it. A fault the anchor did not report
 * is one a pass that looked everywhere did not find, and that is the only evidence of a
 * fix this system accepts.
 *
 * <p>Derived entirely from the stored runs and findings, with nothing kept alongside them.
 * A table of open findings would be a second record of the same facts, maintained by the
 * same code, free to drift from the evidence it summarises. This cannot drift; it is the
 * evidence, read differently.
 *
 * @param sinceRun   the full run the answer is anchored on. Published because it bounds
 *                   the claim: if it is a day old, so is the assurance that anything not
 *                   listed here is genuinely fixed.
 * @param sinceRanAt when that run happened
 * @param findings   sorted by kind then subject, so consecutive readings can be compared
 */
public record OpenFindings(UUID sinceRun, Instant sinceRanAt, List<OpenFinding> findings) {

    public OpenFindings {
        Objects.requireNonNull(sinceRun, "sinceRun must not be null");
        Objects.requireNonNull(sinceRanAt, "sinceRanAt must not be null");
        findings = List.copyOf(findings);
    }

    /**
     * True when nothing is outstanding.
     *
     * <p>Meaningful only next to {@link #sinceRanAt()}. There is no such thing here as a
     * clean answer without a date on it.
     */
    public boolean allClear() {
        return findings.isEmpty();
    }
}
