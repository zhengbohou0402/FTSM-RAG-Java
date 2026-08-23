package com.ftsm.rag.model;

import java.util.Collections;
import java.util.List;

/**
 * Structured outcome of an index rollback operation.
 *
 * <p>{@code consistent=true} means every store (Manifest, Lucene, Qdrant) was
 * fully restored to its pre-transaction state. {@code consistent=false} means
 * at least one store could not be restored and {@code errors} lists the
 * specific failures, so callers can surface the inconsistency instead of
 * silently reporting success.
 */
public final class RollbackResult {

    private final boolean consistent;
    private final List<String> errors;

    public RollbackResult(boolean consistent, List<String> errors) {
        this.consistent = consistent;
        this.errors = errors == null ? Collections.emptyList() : List.copyOf(errors);
    }

    public static RollbackResult success() {
        return new RollbackResult(true, Collections.emptyList());
    }

    public static RollbackResult failure(List<String> errors) {
        return new RollbackResult(false, errors);
    }

    public boolean isConsistent() {
        return consistent;
    }

    public List<String> getErrors() {
        return errors;
    }
}
