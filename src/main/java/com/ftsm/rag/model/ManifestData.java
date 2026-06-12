package com.ftsm.rag.model;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class ManifestData {
    private Map<String, ManifestRecord> documents = new HashMap<>();
    private IndexState index = new IndexState();
}
