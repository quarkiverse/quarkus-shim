package io.quarkiverse.shim.deployment;

/**
 * The resolved mapping of a {@code @ShimBefore}/{@code @ShimAfter} hook's
 * parameters against a specific target overload: whether it receives
 * {@code self}, how many leading target arguments it receives (before only),
 * and whether it receives the returned value (after only).
 */
final class AdviceBinding {

    final ShimOp op;
    final boolean self;
    final int argCount;
    final boolean returned;
    /** For a {@code @ShimCatch} hook, whether it receives the exception being thrown. */
    final boolean throwable;

    AdviceBinding(ShimOp op, boolean self, int argCount, boolean returned) {
        this(op, self, argCount, returned, false);
    }

    AdviceBinding(ShimOp op, boolean self, int argCount, boolean returned, boolean throwable) {
        this.op = op;
        this.self = self;
        this.argCount = argCount;
        this.returned = returned;
        this.throwable = throwable;
    }
}
