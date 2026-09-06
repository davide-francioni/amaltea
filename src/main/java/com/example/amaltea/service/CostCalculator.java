package com.example.amaltea.service;

import com.example.amaltea.model.infrastructure.VmInstance;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * Cost of one run, for comparison against the figures published in the CAPRA paper.
 * <p>
 * <b>The two cost models are not the same quantity, and the thesis has to say so.</b> CAPRA
 * pays <em>per token consumed</em> — about $0.44 per report. A self-hosted SLM pays for
 * <em>VM uptime</em>, regardless of how many documents it processes. Comparing $0.44 against
 * an hourly rate therefore requires an explicit amortisation choice, and the result swings
 * wildly with it: with one document an SLM looks absurdly expensive, with thirty it looks
 * cheap — and thirty is exactly the class-sized scenario the paper uses for its comparison
 * against manual review.
 * <p>
 * {@code documentsProcessed} is a parameter rather than a constant precisely so that the
 * choice is visible in the model instead of buried in a formula.
 */
@Service
public class CostCalculator {

    private static final int MINUTES_PER_HOUR = 60;
    private static final int SCALE = 4;

    /**
     * Cost attributable to one document, amortising VM uptime over the batch.
     * <p>
     * Uptime is billed from creation to teardown, provisioning included: booting, allocating
     * the GPU and loading the weights are paid for even though no document is being processed
     * yet. Excluding them would understate the real cost of the architecture.
     *
     * @param vms                every machine the trial provisioned — one per distinct model,
     *                           not one per role
     * @param documentsProcessed how many documents that uptime served; the amortisation choice
     * @return cost per document, or zero if nothing was processed
     */
    public BigDecimal computeRunCost(List<VmInstance> vms, int documentsProcessed) {
        if (vms == null || vms.isEmpty() || documentsProcessed <= 0) {
            return BigDecimal.ZERO;
        }
        return totalCost(vms).divide(BigDecimal.valueOf(documentsProcessed), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Total cost of a trial: every machine's hourly rate times its own uptime.
     * <p>
     * Machines are summed individually because they need not be identical — during refinement,
     * different roles can sit on differently sized GPUs.
     */
    public BigDecimal totalCost(List<VmInstance> vms) {
        return vms.stream()
                .map(vm -> costOf(vm, vm.uptime()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * What was paid for provisioning alone — boot, GPU allocation, weight loading.
     * <p>
     * Worth reporting separately: it is real money, but it is <em>not</em> part of the figure
     * comparable with the paper's four minutes, which are measured with CAPRA already running.
     */
    public BigDecimal provisioningCost(List<VmInstance> vms) {
        return vms.stream()
                .map(vm -> costOf(vm, vm.provisioningTime()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal costOf(VmInstance vm, Duration duration) {
        if (vm.hourlyCost() == null || duration.isZero() || duration.isNegative()) {
            return BigDecimal.ZERO;
        }
        BigDecimal hours = BigDecimal.valueOf(duration.toMinutes())
                .divide(BigDecimal.valueOf(MINUTES_PER_HOUR), SCALE, RoundingMode.HALF_UP);
        return vm.hourlyCost().multiply(hours);
    }
}
