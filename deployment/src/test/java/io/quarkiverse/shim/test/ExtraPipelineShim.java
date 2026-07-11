package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimBefore;

/**
 * A second, independently named shim on {@link Pipeline}, used by
 * {@link NamedShimTest} to prove per-shim config gating.
 */
@Shim(value = Pipeline.class, name = "pipeline-extra")
public class ExtraPipelineShim {

    @ShimBefore(method = "process")
    public static void extra() {
        CallLog.record("extra");
    }
}
