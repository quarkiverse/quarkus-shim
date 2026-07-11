package io.quarkiverse.shim.deployment;

import java.util.Map;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Build-time configuration for the Shim extension.
 */
@ConfigMapping(prefix = "quarkus.shim")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface ShimBuildTimeConfig {

    /**
     * Whether shim processing is enabled.
     * <p>
     * When set to {@code false}, no target classes are transformed and all
     * {@code @Shim} declarations in the application are ignored — useful for
     * quickly checking behavior against the unpatched classes.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Whether a human-readable dump of every transformed target class is
     * written to {@code target/shim/} during the build. Useful for inspecting
     * exactly what the extension wove into a class.
     */
    @WithDefault("false")
    boolean dumpTransformedClasses();

    /**
     * Per-shim overrides, keyed by the shim's {@code name} (see
     * {@code @Shim(name = ...)}, which defaults to the shim class's simple
     * name). For example {@code quarkus.shim.instances."my-patch".enabled=false}
     * disables just that shim while leaving the rest active.
     */
    Map<String, ShimInstanceConfig> instances();

    interface ShimInstanceConfig {

        /** Whether this individual shim is enabled. */
        @WithDefault("true")
        boolean enabled();
    }
}
