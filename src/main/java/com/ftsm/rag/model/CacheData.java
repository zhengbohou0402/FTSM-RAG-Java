package com.ftsm.rag.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class CacheData {
    private List<CacheEntry> entries = new ArrayList<>();
}
