package com.queryskiff.metrics

import com.queryskiff.datasets.VirtualDatasets
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry

/**
 * HEL-121 observability: Micrometer-backed [VirtualDatasets.Metrics].
 *
 * Emits bounded-cardinality series (outcome/reason/kind are a small fixed set,
 * never the unbounded virtual id) so a Prometheus scrape stays cheap:
 *   queryskiff_virtual_saves_total{outcome="ok|rejected",reason}
 *   queryskiff_virtual_opens_total{outcome="ok|rejected",reason}
 *   queryskiff_virtual_limit_pressure_total{kind="files|bytes"}
 *   queryskiff_virtual_promotions_total
 *   queryskiff_virtual_selection_members  (DistributionSummary)
 *   queryskiff_virtual_selection_bytes    (DistributionSummary)
 * Per-endpoint query latency + 4xx/5xx rejection come free from the Micrometer
 * HTTP-server binding on the /virtual-datasets and /queries routes.
 */
class MicrometerVirtualMetrics(private val registry: MeterRegistry) : VirtualDatasets.Metrics {

    private val members: DistributionSummary =
        DistributionSummary.builder("queryskiff.virtual.selection.members")
            .description("member count of saved virtual selections").register(registry)
    private val bytes: DistributionSummary =
        DistributionSummary.builder("queryskiff.virtual.selection.bytes")
            .baseUnit("bytes")
            .description("total input bytes of saved virtual selections").register(registry)

    override fun saved(memberCount: Int, totalBytes: Long?) {
        registry.counter("queryskiff.virtual.saves", "outcome", "ok", "reason", "none").increment()
        members.record(memberCount.toDouble())
        totalBytes?.let { bytes.record(it.toDouble()) }
    }

    override fun saveRejected(reason: String) =
        registry.counter("queryskiff.virtual.saves", "outcome", "rejected", "reason", reason).increment()

    override fun opened(memberCount: Int) =
        registry.counter("queryskiff.virtual.opens", "outcome", "ok", "reason", "none").increment()

    override fun openRejected(reason: String) =
        registry.counter("queryskiff.virtual.opens", "outcome", "rejected", "reason", reason).increment()

    override fun limitPressure(kind: String) =
        registry.counter("queryskiff.virtual.limit.pressure", "kind", kind).increment()

    override fun promoted() =
        registry.counter("queryskiff.virtual.promotions").increment()
}
