# Quarkus Shim
<!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->
[![All Contributors](https://img.shields.io/badge/all_contributors-1-orange.svg?style=flat-square)](#contributors-)
<!-- ALL-CONTRIBUTORS-BADGE:END -->

Patch any Java class at **build time** — insert, wrap, or replace behavior in code you don't own.

Shim is a Quarkus extension that weaves your patches into target classes during augmentation
(via `BytecodeTransformerBuildItem` + ASM). Because everything happens at build time, patched
classes work in JVM mode, dev mode (with live reload) and GraalVM native image alike — no Java
agent, no runtime instrumentation.

> Note: "shim" here means *modifying existing behavior* in classes you can't edit — not a
> JS-style compatibility polyfill.

## Usage

Declare a shim class annotated with `@Shim`, pointing at the class to patch. Static hook
methods inside it describe the patches:

```java
@Shim(Greeter.class)                       // or @Shim(targetName = "com.acme.internal.Greeter")
public class GreeterShim {

    // Replace the whole body. For instance methods the first parameter receives 'this';
    // remaining parameters and the return type must match the target method.
    @ShimReplace(method = "greet")
    public static String greet(Greeter self, String name) {
        return "Patched " + name;
    }

    // Replace a static method: parameters match exactly.
    @ShimReplace(method = "answer")
    public static int answer() {
        return 42;
    }

    // Run at method entry / before every normal return. Must be static void.
    // Optionally declare a single 'self' parameter (target type or Object) to
    // receive the instance — allowed only on instance target methods.
    @ShimBefore(method = "touch")
    public static void beforeTouch(Greeter self) { /* ... */ }

    @ShimAfter(method = "touch")
    public static void afterTouch() { /* ... */ }
}
```

### Arguments, return value, ordering, and wrapping

`@ShimBefore` can take `self` + a prefix of the target's arguments; `@ShimAfter` can take
`self` + the value about to be returned. Order multiple hooks on one method with
`@ShimPriority` (lower runs first). To run *in place of* the target and call the original,
use `@ShimAround` with a `ShimCall`:

```java
@ShimAround(method = "greet")
public static String greet(ShimCall<String> original, Greeter self, String name) {
    return original.proceed().toUpperCase();   // run the real greet, then transform the result
}
```

When a method name is overloaded, pin the patch to one overload — by parameter types (readable)
or by raw JVM descriptor:

```java
@ShimReplace(method = "format", paramTypes = { int.class })            // readable
@ShimReplace(method = "format", descriptor = "(I)Ljava/lang/String;")  // equivalent
```

## Reaching private and package-private members

**Private fields and methods** — the JVM enforces private access even at the bytecode level,
so hook bodies use the `ShimFields` / `ShimMethods` helpers (cached reflection; every `@Shim`
target class is automatically registered for reflection, so this works in native image too):

```java
@ShimReplace(method = "greet")
public static String greet(Greeter self, String name) {
    int count = ShimFields.<Integer> get(self, "greetCount") + 1;   // private field read
    ShimFields.set(self, "greetCount", count);                      // private field write
    return ShimMethods.invoke(self, "decorate", "Patched " + name); // private method call
}
```

Static members use `ShimFields.getStatic` / `setStatic` and `ShimMethods.invokeStatic`.

**Final fields** — reading is unrestricted, but writing a `final` field via reflection is
fragile (forbidden for `static final` and records, and the JDK is progressively restricting
reflective final mutation). Since Shim already rewrites the target class, list the fields in
`definalize` and the transformer strips their `final` modifier at build time — the write
becomes an ordinary field write:

```java
@Shim(value = Widget.class, definalize = { "name" })
public class WidgetShim {

    @ShimAfter(method = "<init>")
    public static void afterConstruct(Widget self) {
        ShimFields.set(self, "name", "patched"); // 'name' is declared final on Widget
    }
}
```

Static *compile-time constants* (`static final int X = 5`) cannot be definalized — javac
inlines their value into every reader at compile time, so rewriting the field would not
affect them; the build fails with an explanation instead. Note that removing `final` forfeits
the memory-model safe-publication guarantee for that field (only relevant for instances
shared across threads via data races — which post-construction mutation compromises anyway).

**Package-private classes and members — the same-package trick.** Declare the shim class in
the *same package* as the target. Application and dependency classes share the Quarkus
ClassLoader, so they live in the same runtime package — the shim can then name package-private
classes directly, call their package-private methods, and access `protected` members with
plain compiled code (no reflection):

```java
package com.acme.internal;   // same package as the library internals

@Shim(HiddenHelper.class)    // a package-private class — visible from here
public class HiddenHelperShim {

    @ShimReplace(method = "compute")
    public static int compute(HiddenHelper self, int input) {
        return self.packagePrivateMethod(input);   // direct call, no reflection
    }
}
```

**Classes you can't name at all** (e.g. private nested classes): target them with
`@Shim(targetName = "...")` and type the `self` parameter as `Object` — combined with
`ShimFields`/`ShimMethods` this covers classes that can't appear in source.

> **Dev mode caveat.** The same-package trick relies on the shim and target sharing a *runtime
> package* (classloader + name). That holds in JVM production, native image, and for app→app
> shims in dev mode — but dev mode loads application and dependency classes with different
> classloaders, so a shim reaching a *dependency's* package-private/protected member directly
> can throw `IllegalAccessError` in dev mode only. Everything else (the transform, all hooks
> including `@ShimAround`, `ShimFields`/`ShimMethods`, `definalize`, `widenAccess`) behaves
> identically across all three modes. For private access to *dependency* targets, prefer
> `ShimFields`/`ShimMethods` or `widenAccess`, which don't depend on runtime-package identity.

**Widen a whole class.** `@Shim(widenAccess = true)` strips `private`/`final` from all of the
target's members (compile-time constants excepted), making them reflectable without
`setAccessible(true)` and reachable from separately-compiled same-package code. (A shim's own
source still can't reference members that were `private` in the target's source — javac checks
access before the transform; use `ShimFields`/`ShimMethods`, which then need no
`setAccessible`.)

## Diagnostics and gating

- Applied patches are logged at build time and once at startup; a **Dev UI** card ("Applied
  shims") lists them in dev mode.
- `quarkus.shim.dump-transformed-classes=true` writes a readable bytecode dump of each
  transformed class to `target/shim/<class>.txt`.
- `quarkus.shim.enabled=false` disables all shim processing. Each shim has a `name` (default:
  its simple class name); disable one with `quarkus.shim.instances."<name>".enabled=false`.

## Constructors and static initializers

Constructors and static initializers are addressed by their JVM names:

```java
@Shim(Widget.class)
public class WidgetShim {

    @ShimBefore(method = "<init>")            // runs at entry, before super();
    public static void beforeConstruct() { }  // no 'self' — 'this' is not initialized yet

    @ShimAfter(method = "<init>")             // runs after construction, 'self' allowed
    public static void afterConstruct(Widget self) {
        ShimFields.set(self, "size", 99);     // fix up state the constructor got wrong
    }

    @ShimReplace(method = "<clinit>")         // replace the static initializer entirely
    public static void staticInit() { }
}
```

Rules, all enforced at build time:

- `@ShimReplace(method = "<init>")` is rejected: the JVM requires every constructor to call
  `super()`/`this()` before `this` can escape, so constructor bodies cannot be delegated.
  Use `@ShimAfter` + `ShimFields` instead.
- A constructor *before*-hook cannot receive `self` (uninitialized); an *after*-hook can.
- Replacing `<clinit>` discards static field initializers written at the declaration site too —
  they are part of `<clinit>` in bytecode. Set them from the hook (e.g. `ShimFields.setStatic`)
  if needed.
- With constructor chaining (`this(...)`), a hook woven into every overload fires once per
  constructor body entered; pin one overload with `descriptor()` if that matters.

## Semantics and limits

- Patching happens during Quarkus augmentation; only classes loaded through the Quarkus
  ClassLoader can be patched (application classes and indexed dependencies — not JDK classes).
- `@ShimBefore` / `@ShimAfter` hooks are `static void` with no parameters or a single `self`
  parameter. `@ShimAfter` runs before every *normal* return; it does not run when the method
  exits by throwing.
- `@ShimReplace` discards the original body entirely and delegates to your static hook.
  It cannot be combined with `@ShimBefore`/`@ShimAfter` on the same target method.
- Abstract and native methods cannot be shimmed.
- `ShimFields`/`ShimMethods` find members declared in superclasses of the target, but only the
  target class itself is registered for native-image reflection.
- The same-package trick assumes classpath (unnamed module) deployment — standard for Quarkus
  apps. Sealed or signed JARs can reject same-package classes from other JARs (rare).
- Invalid shims (non-static hooks, signature mismatches, unknown target methods, `self` on a
  static target) fail the build with a descriptive error.

## Modules

- `runtime` (`io.quarkiverse.shim:quarkus-shim`) — the annotation API (`@Shim`, `@ShimBefore`,
  `@ShimAfter`, `@ShimReplace`) and the `ShimFields` / `ShimMethods` access helpers.
- `deployment` (`io.quarkiverse.shim:quarkus-shim-deployment`) — Jandex scanning, validation,
  native-image reflection registration, and the ASM class transformer, plus
  `QuarkusExtensionTest`-based tests.
- `docs` (`io.quarkiverse.shim:quarkus-shim-docs`) — Antora documentation following the
  Quarkiverse docs layout (`docs/modules/ROOT/pages/index.adoc`).

## Building

```bash
mvn install
```

Run the tests only:

```bash
mvn test
```

## Contributors ✨

Thanks goes to these wonderful people ([emoji key](https://allcontributors.org/docs/en/emoji-key)):

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tbody>
    <tr>
      <td align="center" valign="top" width="14.28%"><a href="https://fouad.io"><img src="https://avatars.githubusercontent.com/u/1194488?v=4?s=100" width="100px;" alt="Fouad Almalki"/><br /><sub><b>Fouad Almalki</b></sub></a><br /><a href="https://github.com/quarkiverse/quarkus-shim/commits?author=Eng-Fouad" title="Code">💻</a> <a href="#maintenance-Eng-Fouad" title="Maintenance">🚧</a></td>
    </tr>
  </tbody>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!