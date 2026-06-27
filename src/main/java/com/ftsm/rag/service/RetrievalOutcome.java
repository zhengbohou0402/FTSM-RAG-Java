package com.ftsm.rag.service;

import java.util.List;

/**
 * Categorises the outcome of a single retrieval leg (dense / lexical) so the
 * hybrid search orchestrator can distinguish a genuine "no results" from a
 * failure, a timeout, or a thread-pool rejection. This lets the caller apply
 * the correct fallback instead of treating every problem as an empty list.
 */
public enum RetrievalOutcome {
    /** The leg returned successfully with one or more documents. */
    SUCCESS,
    /** The leg completed normally but produced no documents. */
    EMPTY,
    /** The leg threw an exception while executing. */
    FAILED,
    /** The leg did not finish within the configured timeout. */
    TIMEOUT,
    /** The retrieval executor rejected the task (saturation / AbortPolicy). */
    REJECTED;

    /**
     * Wraps a retrieval result together with its outcome and, for failures,
     * the underlying error so the orchestrator can decide how to react.
     */
    public record Outcome<T>(RetrievalOutcome outcome, T value, Throwable error) {

        public static <T> Outcome<T> success(T value) {
            boolean empty = value instanceof List<?> list && list.isEmpty();
            return new Outcome<>(empty ? EMPTY : SUCCESS, value, null);
        }

        public static <T> Outcome<T> failed(Throwable error) {
            return new Outcome<>(FAILED, null, error);
        }

        public static <T> Outcome<T> timeout(Throwable error) {
            return new Outcome<>(TIMEOUT, null, error);
        }

        public static <T> Outcome<T> rejected(Throwable error) {
            return new Outcome<>(REJECTED, null, error);
        }

        public boolean isUsable() {
            return outcome == SUCCESS || outcome == EMPTY;
        }
    }
}
