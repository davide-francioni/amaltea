package com.example.amaltea.model.metrics;

import java.math.BigDecimal;

/**
 * Time and cost of one run, reduced to what can be compared against the CAPRA paper.
 * <p>
 * The paper reports a little over four minutes per report (up to six for long documents),
 * about $0.44 in API cost, against 30–45 minutes of manual review — a speedup of 7.2–10.8×.
 * Those are the figures worth measuring against; token counts, energy and VRAM are not,
 * partly because the paper does not publish per-report token figures at all.
 *
 * @param processingMillis   the figure comparable with the paper's four minutes. The paper
 *                           measures elapsed analysis with CAPRA already running
 * @param provisioningMillis VM boot, GPU allocation and weight loading. Reported separately:
 *                           folding it into the previous figure would make the comparison
 *                           wrong against the SLMs. It remains a real cost for anyone
 *                           adopting this architecture, which is why it is kept rather than
 *                           discarded
 * @param costEuro           cost attributed to this run. Note that the two cost models are
 *                           not the same quantity: CAPRA pays per token, a self-hosted SLM
 *                           pays for VM uptime regardless of how many documents it handles,
 *                           so the comparison needs an explicit amortisation choice
 */
public record RunMetrics(
        long processingMillis,
        long provisioningMillis,
        BigDecimal costEuro
) {}
