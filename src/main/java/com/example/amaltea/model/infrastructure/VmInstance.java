package com.example.amaltea.model.infrastructure;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * A GPU machine created by Terraform to serve one or more roles.
 * <p>
 * Its lifecycle <em>wraps</em> the loop over documents: created once per configuration, used
 * for every test document, then destroyed. Provisioning costs minutes, so repeating it per
 * document would be a large waste.
 *
 * @param id             instance identifier from the cloud provider
 * @param ipAddress      address written into the blueprint handed to CAPRA
 * @param gpuType        fixed for the whole campaign — vLLM guarantees reproducibility only
 *                       on identical hardware, so changing instance type mid-campaign would
 *                       invalidate the comparison without anything signalling it
 * @param hourlyCost     tariff, the only ingredient of the cost calculation besides uptime
 * @param provisionedAt  when creation was requested
 * @param readyAt        when the endpoint actually answered; marks the boundary between
 *                       provisioning time and processing time
 * @param destroyedAt    when it was torn down
 */
public record VmInstance(
        String id,
        String ipAddress,
        String gpuType,
        BigDecimal hourlyCost,
        LocalDateTime provisionedAt,
        LocalDateTime readyAt,
        LocalDateTime destroyedAt
) {
    /** Time spent booting, allocating the GPU and loading the weights. */
    public Duration provisioningTime() {
        if (provisionedAt == null || readyAt == null) return Duration.ZERO;
        return Duration.between(provisionedAt, readyAt);
    }

    /** Total billable time, from creation to teardown. */
    public Duration uptime() {
        if (provisionedAt == null || destroyedAt == null) return Duration.ZERO;
        return Duration.between(provisionedAt, destroyedAt);
    }
}
