package org.yanoproject.x.devtools;

import org.yanoproject.appchain.config.ConfigSourceKind;

/** Redaction-safe summary of a source participating in resolved configuration. */
record ResolvedConfigSource(String name, ConfigSourceKind kind, int ordinal) {
}
