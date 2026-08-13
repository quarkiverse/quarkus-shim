package io.quarkiverse.shim.deployment;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;

/**
 * Decides whether a {@code @Shim} still applies to the dependency version
 * actually on the classpath.
 * <p>
 * The version comes from the resolved application model, so it is the version
 * the build really pulled in — after dependency management and conflict
 * resolution — not what the POM asked for.
 */
final class ShimVersionGate {

    private final ApplicationModel model;
    private final ApplicationArchivesBuildItem archives;

    ShimVersionGate(CurateOutcomeBuildItem curateOutcome, ApplicationArchivesBuildItem archives) {
        this.model = curateOutcome.getApplicationModel();
        this.archives = archives;
    }

    /**
     * The outcome for one shim: either it applies, or it is retired with a
     * human-readable reason.
     */
    record Decision(boolean applies, String coordinates, String actualVersion, String reason) {

        static Decision applied() {
            return new Decision(true, "", "", "");
        }

        static Decision retired(String coordinates, String actualVersion, String reason) {
            return new Decision(false, coordinates, actualVersion, reason);
        }
    }

    /**
     * @param shimClass the annotated shim class, for error messages
     * @param targetClass the class being patched, used to locate the dependency
     *        when {@code dependency} is blank
     * @param dependency {@code "groupId:artifactId"}, or blank to auto-detect
     * @param versions a Maven version range, or blank for a presence-only gate
     */
    Decision evaluate(String shimClass, String targetClass, String dependency, String versions) {
        if (dependency.isBlank() && versions.isBlank()) {
            return Decision.applied();
        }

        String coordinates = dependency.isBlank() ? "" : normalizeKey(shimClass, dependency);
        ResolvedDependency resolved = dependency.isBlank()
                ? containingDependency(shimClass, targetClass)
                : findDependency(coordinates);
        if (resolved == null) {
            return Decision.retired(coordinates, "",
                    "dependency " + coordinates + " is not on the classpath");
        }
        String actual = resolved.getVersion();
        if (coordinates.isBlank()) {
            coordinates = resolved.getGroupId() + ":" + resolved.getArtifactId();
        }
        if (versions.isBlank()) {
            return Decision.applied();
        }
        if (actual == null || actual.isBlank()) {
            throw new IllegalStateException("@Shim on " + shimClass + " is pinned to versions '" + versions
                    + "' but no version could be resolved for " + coordinates);
        }
        if (matches(shimClass, versions, actual)) {
            return Decision.applied();
        }
        return Decision.retired(coordinates, actual,
                coordinates + " is at " + actual + ", outside the pinned range '" + versions + "'");
    }

    /** Whether {@code version} falls inside the {@code versions} specification. */
    static boolean matches(String shimClass, String versions, String version) {
        VersionRange range;
        try {
            range = VersionRange.createFromVersionSpec(versions);
        } catch (InvalidVersionSpecificationException e) {
            throw new IllegalStateException("@Shim on " + shimClass + " declares an invalid version range '"
                    + versions + "': " + e.getMessage(), e);
        }
        ArtifactVersion actual = new DefaultArtifactVersion(version);
        if (range.hasRestrictions()) {
            return range.containsVersion(actual);
        }
        // A bare version ("1.4.2") is only a preference to Maven's resolver; for
        // pinning a patch the useful reading is "exactly this version".
        ArtifactVersion recommended = range.getRecommendedVersion();
        return recommended != null && recommended.compareTo(actual) == 0;
    }

    private ResolvedDependency findDependency(String coordinates) {
        int separator = coordinates.indexOf(':');
        String groupId = coordinates.substring(0, separator);
        String artifactId = coordinates.substring(separator + 1);
        for (ResolvedDependency dependency : model.getDependencies()) {
            if (groupId.equals(dependency.getGroupId()) && artifactId.equals(dependency.getArtifactId())) {
                return dependency;
            }
        }
        ResolvedDependency application = model.getAppArtifact();
        if (application != null && groupId.equals(application.getGroupId())
                && artifactId.equals(application.getArtifactId())) {
            return application;
        }
        return null;
    }

    private ResolvedDependency containingDependency(String shimClass, String targetClass) {
        ApplicationArchive archive = archives.containingArchive(targetClass);
        ResolvedDependency resolved = archive == null ? null : archive.getResolvedDependency();
        if (resolved == null) {
            throw new IllegalStateException("@Shim on " + shimClass
                    + " is pinned to a version range, but the artifact containing " + targetClass
                    + " could not be determined (the class may live in a dependency without a Jandex index)."
                    + " Name it explicitly with dependency = \"groupId:artifactId\"");
        }
        return resolved;
    }

    private static String normalizeKey(String shimClass, String dependency) {
        String trimmed = dependency.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1 || trimmed.indexOf(':', separator + 1) >= 0) {
            throw new IllegalStateException("@Shim on " + shimClass + " declares dependency '" + dependency
                    + "'; expected the form \"groupId:artifactId\" (put the version in versions())");
        }
        return trimmed;
    }
}
