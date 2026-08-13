# Quarkus Shim
    
[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.shim/quarkus-shim?logo=apache-maven&style=flat-square)](https://central.sonatype.com/artifact/io.quarkiverse.shim/quarkus-shim-parent) <!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->[![All Contributors](https://img.shields.io/badge/all_contributors-1-orange.svg?style=flat-square)](#contributors-)<!-- ALL-CONTRIBUTORS-BADGE:END -->

Patch any Java class at **build time** — insert, wrap, or replace behavior in code you don't own.

Shim is a Quarkus extension that weaves your patches into target classes during augmentation
(via `BytecodeTransformerBuildItem` + ASM). Because everything happens at build time, patched
classes work in JVM mode, dev mode (with live reload) and GraalVM native image alike — no Java
agent, no runtime instrumentation.

> Note: "shim" here means *modifying existing behavior* in classes you can't edit — not a
> JS-style compatibility polyfill.

The six kinds of hook:

| | |
|---|---|
| `@ShimBefore`  | run code at method entry; may receive `self` and a prefix of the arguments |
| `@ShimAfter`   | run code before every normal return; may receive `self` and the returned value |
| `@ShimCatch`   | run code when the method exits by throwing; may receive `self` and the exception |
| `@ShimFinally` | run code however the method exits; may receive `self` |
| `@ShimReplace` | replace the method body entirely |
| `@ShimAround`  | wrap the method — call the original via `ShimCall`, transforming args/result |

## Installation

Add the extension to your Quarkus application. With Maven, add the following dependency to
your `pom.xml`:

```xml
<dependency>
    <groupId>io.quarkiverse.shim</groupId>
    <artifactId>quarkus-shim</artifactId>
    <version>${quarkus-shim.version}</version>
</dependency>
```

With Gradle, add to your `build.gradle`:

```groovy
implementation("io.quarkiverse.shim:quarkus-shim:${quarkusShimVersion}")
```

or `build.gradle.kts`:

```kotlin
implementation("io.quarkiverse.shim:quarkus-shim:$quarkusShimVersion")
```

Replace the version placeholder with the latest release from
[Maven Central](https://central.sonatype.com/artifact/io.quarkiverse.shim/quarkus-shim).

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

`ShimCall.proceed()` reruns the original with the arguments the target was called with;
`proceed(...)` runs it with replacements, which is how a hook rewrites what the target sees:

```java
@ShimAround(method = "connect")
public static Conn connect(ShimCall<Conn> original, Client self, String url, int timeoutMs) {
    return original.proceed(url, Math.max(timeoutMs, 5_000));   // clamp a bad default
}
```

### Reacting to failures

`@ShimAfter` deliberately skips the throwing path. To observe a failure, use `@ShimCatch`; to run
on both paths, use `@ShimFinally`. Both are `static void`, and both take an optional `self`;
`@ShimCatch` may also take the exception:

```java
@Shim(FlakyClient.class)
public class FlakyClientShim {

    // the exception is rethrown unchanged once the hook returns
    @ShimCatch(method = "send", exception = IOException.class)
    public static void onFailure(FlakyClient self, IOException failure) {
        Metrics.counter("flaky.send.failures").increment();
    }

    @ShimFinally(method = "send")
    public static void always(FlakyClient self) {
        ShimFields.<Semaphore> get(self, "inFlight").release();
    }
}
```

The target's own `catch` blocks keep priority: an exception the method already handles itself
never reaches `@ShimCatch`, while `@ShimFinally` still runs because the method returns normally.
Neither can target a constructor — a handler covering the constructor body could observe `this`
before `super()` has run, which the verifier rejects.

When a method name is overloaded, pin the patch to one overload — by parameter types (readable)
or by raw JVM descriptor:

```java
@ShimReplace(method = "format", paramTypes = { int.class })            // readable
@ShimReplace(method = "format", descriptor = "(I)Ljava/lang/String;")  // equivalent
```

### Attaching annotations

Put `@ShimAnnotate` on a shim class, method, or field and declare the annotations to copy on
the same template element:

```java
@Shim(LegacyService.class)
@ShimAnnotate
@Deprecated(since = "shim")
public class LegacyServiceShim {

    @ShimAnnotate(target = "state")
    @Deprecated(since = "shim")
    Object stateAnnotations;

    @ShimAnnotate(target = "run", paramTypes = String.class)
    @Deprecated(since = "shim")
    void runAnnotations() {}
}
```

On a class, annotations are attached to the target class. On a method or field, `target`
defaults to the template member's name; method overloads can be selected with `paramTypes` or
`descriptor`. Annotation values and `RUNTIME`/`CLASS` retention visibility are preserved.

If the target already declares the same annotation type, the shim annotation replaces it by
default. Set `onConflict = AnnotationConflict.KEEP` to retain the target annotation, or
`onConflict = AnnotationConflict.FAIL` to fail augmentation instead. `REPLACE` can also be
specified explicitly.

Attachment is a bytecode transformation, so reflection and other JVM consumers see the
annotations after augmentation. Quarkus build steps that only read the immutable Jandex index
do not; `@ShimAnnotate` is therefore not a way to add build-time annotations such as CDI scopes
or REST endpoints.

## Pinning a shim to a dependency version

A patch for someone else's bug is a bandaid: it is written against the exact release that has
the bug, and it should come off when that release is upgraded. Declare the versions the patch
belongs to and Shim stops applying it once the dependency moves past them:

```java
@Shim(value = DecisionEngine.class,
      name = "fail-closed-decision",
      dependency = "com.acme:decision-engine",   // groupId:artifactId of the library
      versions = "[1.2,1.5)")                    // the releases this patch was written for
public class DecisionEngineShim {

    @ShimReplace(method = "isAllowed", paramTypes = String.class)
    public static boolean isAllowed(String decision) {
        return "ALLOW".equalsIgnoreCase(decision);
    }
}
```

Upgrade `com.acme:decision-engine` to 1.5 and the target class is left untouched — the vendor's
own code runs, and the build warns that the shim did not apply so it can be deleted:

```
WARN  Shim 'fail-closed-decision' (com.acme.DecisionEngineShim) was not applied to
      com.acme.DecisionEngine: com.acme:decision-engine is at 1.5.0, outside the pinned
      range '[1.2,1.5)'
```

The warning repeats once at startup, and dev mode lists retired shims in a "Retired shims" Dev
UI table next to the applied ones.

`versions` takes standard Maven range syntax — `[1.2,1.5)` (1.2 up to but excluding 1.5),
`(,2.0)`, `[2.0,)`, `[1.2,1.3],[1.5,1.6]`, or a bare `1.4.2` meaning *exactly* that version —
matched against the version the build actually resolved. Comparison is Maven's, so `1.10` is
above `1.9` and `1.5-SNAPSHOT` sits just below `1.5`.

Leave `dependency` out and the artifact containing the target class is used, which is usually
what you want. Name it explicitly when the target class is not in the artifact whose version
should decide, or when it lives in a dependency without a Jandex index. Used on its own,
`dependency` is a presence gate: the shim applies only while that artifact is on the classpath.

By default a shim that no longer applies is retired with a warning. For a patch that must not
disappear without someone looking at it, make the mismatch stop the build instead:

```java
@Shim(value = DecisionEngine.class, dependency = "com.acme:decision-engine",
      versions = "[1.2,1.5)", onVersionMismatch = VersionMismatch.FAIL)
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

Static members use `ShimFields.getStatic` / `setStatic` and `ShimMethods.invokeStatic`, and
`ShimMethods.newInstance` reaches a private constructor. Overload resolution follows Java's own
rules closely: widening primitive conversion applies, the most specific overload wins, varargs
are supported, and compiler-generated bridge methods are ignored so a covariant override or a
generic interface implementation resolves cleanly. When overloads still cannot be inferred from
runtime values (especially `null`), use `ShimMethods.invokeExact` / `invokeStaticExact` with an
explicit `Class<?>[]` signature.

Both helpers search superclasses and then interfaces, so inherited members and interface
constants are reachable. Lookups start from the runtime class of the instance, so a subclass
field that shadows one on the target wins — `ShimFields.getDeclared` / `setDeclared` name the
declaring class explicitly when that matters. Whatever the target throws propagates unchanged,
including checked exceptions.

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

- Applied patches are logged at build time and once at startup; a **Dev UI** card lists them in
  dev mode ("Applied shims"), alongside a "Retired shims" table for those held back by a version
  pin.
- `quarkus.shim.dump-transformed-classes=true` writes a readable bytecode dump of each
  transformed class to the module's build output under `shim/<class>.txt`. The dump is written
  even when the weave fails validation, which is when you most want it.
- `quarkus.shim.verify-transformed-classes=true` runs each woven class through ASM's
  `CheckClassAdapter`, turning a structural problem into a build failure instead of a
  `ClassFormatError` or `VerifyError` at class-load time. Worth enabling while developing a shim
  against an unusual target.
- A `@Shim` whose target is in no application archive fails the build rather than being reported
  as applied: a class Quarkus cannot locate is a class it cannot transform, and that is almost
  always a typo in `targetName` or a dependency without a Jandex index.
- `quarkus.shim.enabled=false` disables all shim processing. Each shim has a `name` (default:
  its simple class name); disable one with `quarkus.shim.instances."<name>".enabled=false`.

## Constructors and static initializers

Constructors and static initializers are addressed by their JVM names:

```java
@Shim(value = Widget.class, definalize = { "DEFAULTS" })
public class WidgetShim {

    @ShimBefore(method = "<init>")            // runs at entry, before super();
    public static void beforeConstruct() { }  // no 'self' — 'this' is not initialized yet

    @ShimAfter(method = "<init>")             // runs after construction, 'self' allowed
    public static void afterConstruct(Widget self) {
        ShimFields.set(self, "size", 99);     // fix up state the constructor got wrong
    }

    // Widget must actually have a static initializer for this to apply — a class
    // with no static field initializers and no static block has no <clinit>.
    @ShimReplace(method = "<clinit>")         // replace the static initializer entirely
    public static void staticInit() {
        // the original <clinit> is gone, so every static field it set is now unset;
        // 'DEFAULTS' is listed in definalize above so it can be written here
        ShimFields.setStatic(Widget.class, "DEFAULTS", Map.of("mode", "patched"));
    }
}
```

Rules, all enforced at build time:

- `@ShimReplace(method = "<init>")` is rejected: the JVM requires every constructor to call
  `super()`/`this()` before `this` can escape, so constructor bodies cannot be delegated.
  Use `@ShimAfter` + `ShimFields` instead.
- A constructor *before*-hook cannot receive `self` (uninitialized); an *after*-hook can.
- Replacing `<clinit>` discards static field initializers written at the declaration site too —
  they are part of `<clinit>` in bytecode. Set them from the hook with `ShimFields.setStatic`,
  and list any `static final` field you assign in `definalize` — otherwise the write is rejected
  and the class fails initialization permanently with `NoClassDefFoundError`.
- With constructor chaining (`this(...)`), a hook woven into every overload fires once per
  constructor body entered; pin one overload with `descriptor()` if that matters.

## Semantics and limits

- Patching happens during Quarkus augmentation; only classes loaded through the Quarkus
  ClassLoader can be patched (application classes and indexed dependencies — not JDK classes).
- `@ShimBefore` / `@ShimAfter` / `@ShimCatch` / `@ShimFinally` hooks are `static void`. Their
  parameters are optional and positional — see each annotation for the exact list. A hook
  parameter that could equally be read as `self` or as an argument/returned value (typically a
  lone `Object`) is rejected at build time rather than silently bound to one of them.
- `@ShimAfter` runs before every *normal* return; it does not run when the method exits by
  throwing. `@ShimCatch` runs only on the throwing path, `@ShimFinally` on both.
- `@ShimReplace` discards the original body entirely and delegates to your static hook.
  It cannot be combined with any other hook on the same target method, and neither can
  `@ShimAround`.
- Abstract and native methods cannot be shimmed. Compiler-generated bridge methods are skipped,
  so a hook on a covariant override or a generic interface implementation fires once per call
  however the caller reached it.
- `ShimFields`/`ShimMethods` find members declared in superclasses and interfaces of the target.
  The target and its indexed superclasses are registered for native-image reflection; a
  superclass outside the Jandex index is not, so index the dependency if you reach into it.
- The same-package trick assumes classpath (unnamed module) deployment — standard for Quarkus
  apps. Sealed or signed JARs can reject same-package classes from other JARs (rare).
- Invalid shims (non-static hooks, signature mismatches, unknown target methods, `self` on a
  static target) fail the build with a descriptive error.

## Modules

- `runtime` (`io.quarkiverse.shim:quarkus-shim`) — the annotation API (`@Shim`, `@ShimBefore`,
  `@ShimAfter`, `@ShimReplace`, `@ShimAround`, `@ShimAnnotate`, `AnnotationConflict`,
  `VersionMismatch`) and the `ShimFields` / `ShimMethods` access helpers.
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
