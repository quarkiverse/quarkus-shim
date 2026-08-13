# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

## 0.4.0 - 2026-08-14

### Added

- `@ShimCatch` and `@ShimFinally` hooks for the exceptional exit path: `@ShimCatch` observes the
  exception a target throws (optionally narrowed with `exception = ...`) and rethrows it
  unchanged, `@ShimFinally` runs however the method exits. The target's own `catch` blocks keep
  priority, so an exception it already handles never reaches the hook.
- `ShimCall.proceed(Object...)` runs the original method body with replacement arguments, so an
  `@ShimAround` hook can rewrite what the target sees.
- `ShimMethods.newInstance` for constructing an instance through a private constructor, and
  `ShimFields.getDeclared`/`setDeclared` for naming the declaring class when a subclass shadows a
  field.
- `quarkus.shim.verify-transformed-classes` runs each woven class through ASM's
  `CheckClassAdapter`, turning a structural problem into a build failure instead of a
  `ClassFormatError` or `VerifyError` at class-load time.
- `quarkus.shim.report` writes a `shim-report.txt` summary of applied and retired shims to the
  build output directory.

### Fixed

- A target class with any generic method no longer fails the build. Descriptors are now built
  from the erased signature Jandex reports for the class file, so a type variable anywhere on the
  target is irrelevant to a shim that never named that method. The same change makes enum and
  inner-class constructors selectable, which previously validated against a descriptor the JVM
  never sees.
- Hooks are no longer woven into compiler-generated bridge methods, so a hook on a covariant
  override or a generic interface implementation fires once per call instead of twice when the
  caller went through the bridge. `@ShimAnnotate` no longer annotates bridges either, and
  `ShimMethods` no longer reports them as an ambiguous overload.
- `definalize` and `widenAccess` no longer strip `final` from interface fields, which produced a
  class the JVM refused to load. `widenAccess` also keeps `final` on compile-time constants, as
  its javadoc always claimed, and now widens constructors.
- A `@ShimBefore`/`@ShimAfter` hook whose parameter is equally readable as `self` or as an
  argument/returned value is rejected with an explanatory error instead of silently binding to
  `self` — the documented `Object`-typed `returned` parameter used to receive `this`.
- `@ShimAround` raises the emitted class file version to 51 when needed, so a target compiled for
  Java 6 or older stays loadable after the `invokedynamic` is woven in.
- `AnnotationConflict.FAIL` is honoured when either side of a shim-to-shim collision declares it,
  rather than depending on the order shims are discovered.
- Attached annotations are emitted in ASM's prescribed visit order.
- A hook the target cannot reach (not public, in another package) and a shim name claimed by two
  classes are rejected at build time instead of failing later.
- Hook call sites use an `InterfaceMethodref` when the shim is an interface.
- `ShimMethods` applies widening primitive conversion, resolves varargs methods, picks the most
  specific applicable overload, searches interfaces as well as superclasses, and rethrows what
  the target threw instead of wrapping checked exceptions in `IllegalStateException`. Neither
  helper caches against bootstrap-loaded classes, which retained application classes across
  dev-mode restarts.

### Changed

- A `@Shim` whose target is in no application archive now fails the build. Such a class can never
  be transformed, but was previously logged, listed in the Dev UI, and reported at startup as an
  applied patch.
- The transformed-class dump is written under the module's build output directory instead of the
  process working directory, and is written on the failure path too.
- The bytecode transformer is registered with an explicit priority so its position relative to
  other extensions' transformers is deliberate.
- Configuration naming a shim that does not exist is reported as a warning rather than silently
  ignored.
- Documentation corrections: the hook parameter rules, native-image reflection registration, the
  `<clinit>` replacement example and its recovery advice, `@ShimAnnotate` retention, the
  `@ShimPriority` tie-break, the dev-mode caveat on the same-package trick, and the merging of
  several `@Shim` classes on one target.

## 0.3.0 - 2026-08-13

### Added

- Version pinning for shims: `@Shim(dependency = "groupId:artifactId", versions = "[1.2,1.5)")`
  applies a patch only while the resolved dependency version falls inside the Maven range, so
  upgrading past the patched releases retires the shim instead of weaving a stale patch. The
  dependency defaults to the artifact containing the target class; used without `versions` it is
  a presence gate.
- `VersionMismatch` policy for pinned shims: `SKIP` (default) retires the shim with a warning at
  build time, at startup, and in a "Retired shims" Dev UI table; `FAIL` stops augmentation so the
  patch must be reviewed before the dependency moves.

## 0.2.0 - 2026-07-19

### Added

- `@ShimAnnotate` for attaching annotations to target classes, methods, and fields during
  augmentation, including method-overload selection and preservation of annotation values and
  retention visibility.
- `AnnotationConflict` policies for existing target annotations: replace them by default, keep
  them, or fail augmentation with `REPLACE`, `KEEP`, and `FAIL`.

## 0.1.1 - 2026-07-13

### Added

- Regression coverage for method metadata preservation, primitive return advice, missing targets,
  conflicting hooks, access widening, interface default methods, reflection cache isolation, exact
  overload selection, and native reflection hierarchy registration.
- `ShimMethods.invokeExact` and `ShimMethods.invokeStaticExact` for explicitly selecting overloaded
  methods, including calls with `null` arguments.

### Fixed

- Preserve method annotations and declaration metadata when replacing or wrapping method bodies.
- Box primitive return values before passing them to `@ShimAfter` hooks that accept `Object`.
- Fail transformation when hooks or `definalize` entries do not match a target member.
- Reject multiple `@ShimReplace` or `@ShimAround` hooks for the same target method.
- Apply `widenAccess` consistently to methods wrapped with `@ShimAround`.
- Generate valid interface method references when wrapping default methods.
- Isolate cached reflective fields and methods by their defining class and classloader.
- Register indexed target superclasses for native reflection.

### Changed

- Updated the Quarkus Dev UI deployment dependency and pinned the Quarkus extension Maven plugin.
- Replaced deprecated Jandex array component access with the current API.

## 0.1.0 - 2026-07-12

First release of Quarkus Shim.

### Added

- Build-time bytecode patching through `@Shim`, with `@ShimBefore`, `@ShimAfter`,
  `@ShimReplace`, and `@ShimAround` hooks.
- Overload selection by parameter types or JVM descriptor and deterministic hook ordering with
  `@ShimPriority`.
- Reflective field and method helpers for accessing private target members.
- Field definalization and whole-class access widening.
- Constructor and static-initializer hooks.
- Per-shim configuration, transformed-class diagnostics, Dev UI reporting, and native-image
  reflection registration.
