package io.quarkiverse.shim.deployment;

/**
 * A single patch operation collected from a {@code @Shim} class: which target
 * method to touch, how, and which static hook method to invoke. The precise
 * binding (which of the hook's parameters are self/args/returned) is resolved
 * against the actual target overload at weave time by {@link ShimClassVisitor}.
 */
final class ShimOp {

    enum Kind {
        BEFORE,
        AFTER,
        REPLACE,
        AROUND
    }

    final Kind kind;
    final int priority;
    final String targetMethodName;
    /**
     * Filter for the target overload; empty matches every overload. When
     * {@link #matchParamsOnly} is true this is a parameters-only descriptor
     * like {@code "(Ljava/lang/String;I)"}; otherwise a full method descriptor.
     */
    final String targetMethodDescriptor;
    final boolean matchParamsOnly;
    final String shimOwnerInternalName;
    final String shimMethodName;
    final String shimMethodDescriptor;
    /** The shim class simple/logical name, for diagnostics. */
    final String shimName;

    ShimOp(Kind kind, int priority, String targetMethodName, String targetMethodDescriptor, boolean matchParamsOnly,
            String shimOwnerInternalName, String shimMethodName, String shimMethodDescriptor, String shimName) {
        this.kind = kind;
        this.priority = priority;
        this.targetMethodName = targetMethodName;
        this.targetMethodDescriptor = targetMethodDescriptor;
        this.matchParamsOnly = matchParamsOnly;
        this.shimOwnerInternalName = shimOwnerInternalName;
        this.shimMethodName = shimMethodName;
        this.shimMethodDescriptor = shimMethodDescriptor;
        this.shimName = shimName;
    }

    boolean matches(String methodName, String methodDescriptor) {
        if (!targetMethodName.equals(methodName)) {
            return false;
        }
        if (targetMethodDescriptor.isEmpty()) {
            return true;
        }
        if (matchParamsOnly) {
            return methodDescriptor.substring(0, methodDescriptor.indexOf(')') + 1)
                    .equals(targetMethodDescriptor);
        }
        return targetMethodDescriptor.equals(methodDescriptor);
    }

    String hookRef() {
        return shimOwnerInternalName.replace('/', '.') + "#" + shimMethodName;
    }

    /** Human-readable one-liner for diagnostics: "target#method [kind] <- Shim#hook". */
    String describe(String targetClass) {
        return targetClass + "#" + targetMethodName + " [" + kind.name().toLowerCase() + "] <- " + hookRef();
    }
}
