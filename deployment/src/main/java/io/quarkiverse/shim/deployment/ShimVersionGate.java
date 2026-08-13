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

        static Decision applied(String coordinates, String actualVersion) {
            return new Decision(true, coordinates, actualVersion, "");
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
            // presence gate: the dependency is here, which is all that was asked
            return Decision.applied(coordinates, actual == null ? "" : actual);
        }
        if (actual == null || actual.isBlank()) {
            throw new IllegalStateException("@Shim on " + shimClass + " is pinned to versions '" + versions
                    + "' but no version could be resolved for " + coordinates);
        }
        if (matches(shimClass, versions, actual)) {
            return Decision.applied(coordinates, actual);
        }
        return Decision.retired(coordinates, actual,
                coordinates + " is at " + actual + ", outside the pinned range '" + versions + "'");
    }

    /**
     * Whether {@code version} falls inside the {@code versions} specification.
     * <p>
     * Comparison is Maven's, so a qualifier sorts below the release it precedes:
     * {@code 1.5-SNAPSHOT} is inside {@code [1.2,1.5)} and outside
     * {@code [1.5,)}. Pin with that in mind when a patch must retire before the
     * release it was written against ships.
     */
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
        String[] segments = coordinates.split(":", -1);
        String groupId = segments[0];
        String artifactId = segments[1];
        String classifier = segments.length > 2 ? segments[2] : null;
        for (ResolvedDependency dependency : model.getDependencies()) {
            if (!groupId.equals(dependency.getGroupId()) || !artifactId.equals(dependency.getArtifactId())) {
                continue;
            }
            if (classifier != null && !classifier.equals(dependency.getClassifier())) {
                continue;
            }
            return dependency;
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
        if (archive == null) {
            throw new IllegalStateException("@Shim on " + shimClass
                    + " is pinned to a version, but no application archive contains " + targetClass
                    + " — the class may live in a dependency without a Jandex index."
                    + " Index it, or name the artifact explicitly with dependency = \"groupId:artifactId\"");
        }
        ResolvedDependency resolved = archive.getResolvedDependency();
        if (resolved == null) {
            throw new IllegalStateException("@Shim on " + shimClass
                    + " is pinned to a version, but the archive containing " + targetClass
                    + " has no Maven coordinates to read a version from (it is typically the application's own"
                    + " classes). Name the artifact explicitly with dependency = \"groupId:artifactId\"");
        }
        return resolved;
    }

    /**
     * Accepts {@code groupId:artifactId} with an optional {@code :classifier},
     * trimming each segment so an incidental space does not silently retire the
     * shim by failing to match any dependency.
     */
    private static String normalizeKey(String shimClass, String dependency) {
        String[] segments = dependency.split(":", -1);
        boolean wellFormed = segments.length == 2 || segments.length == 3;
        if (wellFormed) {
            for (int i = 0; i < segments.length; i++) {
                segments[i] = segments[i].trim();
                // only the classifier may be empty, meaning "the default artifact"
                if (segments[i].isEmpty() && i < 2) {
                    wellFormed = false;
                }
            }
        }
        if (!wellFormed) {
            throw new IllegalStateException("@Shim on " + shimClass + " declares dependency '" + dependency
                    + "'; expected \"groupId:artifactId\" or \"groupId:artifactId:classifier\""
                    + " (put the version in versions())");
        }
        return String.join(":", segments);
    }
}
