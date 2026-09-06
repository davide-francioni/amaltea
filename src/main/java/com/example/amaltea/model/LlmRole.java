package com.example.amaltea.model;

/**
 * The six points at which CAPRA consumes an LLM.
 * <p>
 * This enum is the integration contract between AMALTEA and CAPRA: it is the list of what
 * has to be configured. An identical copy lives in CAPRA — the shared-contract decision is
 * still parked, so for now the two definitions are duplicated and must be kept aligned by
 * hand. CAPRA should fail on startup if a blueprint names a role it does not know, rather
 * than ignoring it silently.
 * <p>
 * Verified against CAPRA's source: five consumers use {@code analysisChatClient}
 * (GPT-5.1), one uses {@code reportChatClient} (Claude Haiku 4.5).
 */
public enum LlmRole {

    /**
     * Requirements and use-case analysis.
     * Called {@code SpecificationAuditorAgent} in the CAPRA paper.
     */
    REQUIREMENTS_AGENT,

    /** Test coverage audit. */
    TEST_AUDITOR_AGENT,

    /** Expected-feature coverage check, backed by the MongoDB knowledge base. */
    FEATURE_CHECK_AGENT,

    /** Use case to design to test traceability matrix. */
    TRACEABILITY_MATRIX_AGENT,

    /** Meta-agent: verification, deduplication and renumbering of candidate issues. */
    CONSISTENCY_MANAGER,

    /** LaTeX report generation. The only role served by Anthropic in the original setup. */
    LATEX_REPORT
}
