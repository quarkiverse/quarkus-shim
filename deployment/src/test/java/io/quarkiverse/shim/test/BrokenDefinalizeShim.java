package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;

/**
 * Invalid on purpose: {@code Widget.CONST} is a static compile-time constant,
 * which cannot be definalized (its value was inlined into every reader by
 * javac). Only added to the archive of {@link InvalidDefinalizeTest}.
 */
@Shim(value = Widget.class, definalize = { "CONST" })
public class BrokenDefinalizeShim {
}
