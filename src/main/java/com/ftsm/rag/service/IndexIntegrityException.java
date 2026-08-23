package com.ftsm.rag.service;

/**
 * Thrown when a pre-modification integrity check detects that the live index
 * is already inconsistent (for example a chunk ID present in the Manifest but
 * missing from the Qdrant or Lucene backup). In that state the system refuses
 * to mutate the index, because a rollback would not be able to restore a known
 * good state.
 */
public class IndexIntegrityException extends RuntimeException {

    public IndexIntegrityException(String message) {
        super(message);
    }

    public IndexIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
