# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

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
