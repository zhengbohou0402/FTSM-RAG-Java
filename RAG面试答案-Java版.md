# RAG 面试题答案（全 20 题 · FTSM-RAG Java 项目实战版）

> 项目：**FTSM-RAG** — 马来西亚国立大学 UKM FTSM 学院知识问答系统
> 技术栈：Spring Boot WebFlux · Qdrant · LangChain4j · Google Vertex AI (gemini-3.5-flash + gemini-embedding-001)

---

## Q1. 什么是 RAG？详细描述一个完整 RAG 系统的详细工作流程？

### 概念定义

RAG（Retrieval-Augmented Generation，检索增强生成）是一种将**外部知识库检索**和**大语言模型生成**结合的架构范式。核心思想是：LLM 生成时不只依赖自身训练参数，而是先从外部知识库中取回相关上下文，再基于这些"证据"生成回答。

### 我项目的完整工作流程

    用户提问（中/英/马来语均可）
      │
      ▼
    ① 查询预处理（com.ftsm.rag.utils.QueryPreprocessor）
      ├─ 繁体中文 → 简体中文（OpenCC4J）
      ├─ 全角/半角归一化
      ├─ 导航式问题改写：
      │    "where can I find..." → 去掉导航词，保留主题词
      ├─ 话题关键词扩展（TOPIC_REWRITE_RULES）：
      │    如 "签证" → "student visa renewal student pass EMGS PKP"
      └─ 三语同义词扩展（SYNONYM_MAP，含中/英/马来语 200+ 词对）：
           如 "签证" → ["student pass", "permit pelajar", "EMGS", "eVisa"]
      最终输出：主查询 + 最多 3 条扩展查询
      │
      ▼
    ② 语义缓存检查（com.ftsm.rag.service.SemanticCacheService）
      ├─ 对主查询调用 gemini-embedding-001 生成向量
      ├─ 与缓存中所有历史问题向量计算余弦相似度
      ├─ 相似度 ≥ 0.92 → HIT，直接返回缓存答案，跳过全流程
      └─ 未命中 → 继续
      │
      ▼
    ③ 混合检索（RagService.java · retrieveDocs）
      ├─ Dense 检索：gemini-embedding-001（3072维）→ Qdrant 余弦相似度
      ├─ Sparse 检索：Lucene BM25 关键词匹配（LexicalIndexService）
      ├─ 底层 RRF（Reciprocal Rank Fusion）融合两路结果
      └─ Reactor Flux + Schedulers.boundedElastic() 并发执行所有扩展查询，结果合并去重
      │
      ▼
    ④ 重排序（RagService.java · rerank + applySourceWeight + applyQueryBoost）
       ├─ VertexAiRankingClient.rerank 精排（Discovery Engine semantic-ranker-512@latest），保留 top-6 (RERANK_TOP_N)
       ├─ applySourceWeight：官方资料(+0.25) > 爬取资料(+0.10) > 社区指南(-0.15) > 生成摘要(-0.25)
       └─ applyQueryBoost：课程代码+5.0 / 关键短语+4.0 / 教室编号+3.0 / 年份+1.25 / 地图类词+3.0
      │
      ▼
    ⑤ 检索信号验证（RagService.java · hasRetrievalSignal）
      ├─ 提取查询关键词（getQueryTerms，过滤 STOP_WORDS）
      ├─ 检查 top-3 文档与关键词的词项重叠率
      ├─ 重叠词 ≥ 阈值 → 有信号，继续生成
      └─ 无信号 → 直接返回固定拒绝语句 (NO_ANSWER_MESSAGE)，不调用 LLM
      │
      ▼
    ⑥ Prompt 构建 + LLM 生成（RagService.java · streamAnswer）
      ├─ 格式化上下文：[Reference 1] Content: ... | Metadata: ...
      ├─ 填充 rag_summarize.txt，调用 gemini-3.5-flash (Vertex AI)
      └─ 通过 StreamingResponseHandler 和 Reactor Flux<String> 流式输出给前端
      │
      ▼
    ⑦ 来源引用格式化（formatSources & formatSourceReliability）
      └─ 每条引用展示：文件名 [可信度标签] chunk序号: 摘要文字

---

## Q2. 大模型的 RAG 主要用来解决什么问题？

| 核心问题 | RAG 的解法 | 我项目的体现 |
| --- | --- | --- |
| **知识时效性** | 接入可实时更新的外部知识库 | `FtsmWebsiteCrawler` 和 `CrawlScheduler` 定时抓取 FTSM 官网，重建索引 |
| **幻觉（Hallucination）** | 强制模型基于检索文档回答 | `hasRetrievalSignal` 验证：无证据时直接拒绝，不让 LLM 猜测 |
| **私域知识缺失** | 接入私有知识库 | 学院内部 PDF、TXT（利用 `FileExtractors`）全部入库 |
| **可溯源性** | 展示引用的原始文档 | Source Cards：展示文件名、chunk 序号、摘要、可信度（如 Official material） |
| **上下文长度限制** | 只传最相关的 top-N 片段 | top-6 (RERANK_TOP_N) 片段构建 context，而非传全量文档 |
| **API 成本** | 语义缓存减少重复调用 | 余弦相似度 0.92 的 `SemanticCacheService` |

---

## Q3. 相比直接微调 LLM，RAG 解决了什么问题？微调和 RAG 各自的优劣势？

### 对比表

| 维度  | 微调（Fine-tuning） | RAG |
| --- | --- | --- |
| **知识更新** | 重训练，成本高、周期长 | 更新文档即生效，无需重训 |
| **成本** | GPU 算力 + 高质量标注数据 | 只需维护向量数据库 (Qdrant) |
| **幻觉控制** | 依赖数据质量，难以彻底消除 | 无检索信号时直接拒答，可控 |
| **可解释性** | 黑盒  | 可追溯到具体文档 chunk |
| **灾难性遗忘** | 有风险 | 不影响基础模型能力 |
| **风格/格式控制** | ✅ 强 | ❌ 弱（依赖 prompt） |
| **知识密集型问答** | ❌ 时效差 | ✅ 强 |

### 我的选择理由

FTSM 学院信息频繁更新（每学期校历、考试时间表、新开课程），微调需周期性重训、成本高；RAG 只需利用 `IndexingService` 重建 Qdrant 索引，且可溯源，适合问答场景。

---

## Q4. RAG 中的文档是怎么存的？粒度是多大？详细说说文档切割（Chunking）策略？

### 存储架构

    知识文件（TXT/PDF）
        ↓ 加载（FileExtractors.java 解析文本）
        ↓ PythonCompatibleTextSplitter 切块
        ↓ gemini-embedding-001 向量化 (ModelFactory.java)
        ↓ Qdrant 持久化存储（向量 + payload） (VectorStoreService.java)
        ↓ DocumentManifestManager 跟踪（索引状态 manifest）

### Chunking 参数（在 VectorStoreService.java 中定义）

    "chunk_size": 800       # AppConfig 注入
    "chunk_overlap": 120    # AppConfig 注入
    "splitter": "python-recursive-character-v1"
    "separators": DEFAULT_SEPARATORS
      - "\n\n"            # 段落边界（优先）
      - "\n"              # 行边界
      - ". "              # 句子边界
      ...

### 每个 Chunk 的 Qdrant Payload（元数据）

    {
        "doc_id":            "file:data/ukm_ftsm/academic_calendar.pdf",
        "chunk_id":          "uuid5(doc_id:chunk:0)",   # 确定性 UUID，便于增量更新
        "chunk_index":       0,
        "source_type":       "official",
        "source_trust_label":"Official material",
        "source_priority":   1,                         # 数字越小越权威
        "file_path":         "...",
        "hash":              "sha256...",               # 文件指纹
        "loader_page_number": 2,                        # PDF 页码
    }

---

## Q5. 怎么规避语义被切割掉的问题？

### 1. PythonCompatibleTextSplitter（语义边界优先）

不按固定字符截断，而是按段落 → 句子 → 单词递归切割，确保切割点尽量落在语义边界。

### 2. chunk_overlap = 120 字符（滑动窗口）

相邻 chunk 共享 120 字符，保证跨块语义连续。例如"前半句在 chunk-0 末尾，后半句在 chunk-1 开头"，两块都包含完整语义。

### 3. 多查询并发检索（Multi-Query Fusion）

    // QueryPreprocessor 扩展查询
    List<String> queries = queryPreprocessor.process(query);
    
    // RagService 并发检索
    Flux.fromIterable(queries)
        .flatMap(singleQuery -> Mono.fromCallable(() -> vectorStoreService.search(singleQuery, limit))
        .subscribeOn(Schedulers.boundedElastic()) // Reactor 并发
        ...

多角度查询并发检索，再合并去重（`documentKey` 去重）。即便关键语义被切断，多路查询也能兜底召回。

### 4. Hybrid Search（Dense + Sparse 互补）

* **Dense**（gemini-embedding-001）：语义相近即可召回。
* **Sparse**（Lucene BM25 / Qdrant）：关键词精确匹配，弥补语义向量在专有名词上的盲区。

### 5. Query Boost（关键标识符强化）

`RagService.java` 中的 `applyQueryBoost` 方法：对课程代码、教室编号、年份等结构化标识符，命中时额外加分（+1.0～+5.0），确保包含精确标识符的 chunk 不被语义泛化掩盖。

---

## Q6. 在 RAG 中 Embedding 究竟是什么？如何选择和评估一个 Embedding 模型？

### Embedding 是什么

将文本映射为高维实数向量，使得语义相近的文本在向量空间中距离近。RAG 中 embedding 承担两个职责：
* **索引期**：将知识库文档每个 chunk 向量化并存入 Qdrant。
* **查询期**：将用户问题向量化，做近似最近邻（ANN）搜索。

### 我项目的选型演进

| 阶段  | 模型  | 维度  | 淘汰原因 |
| --- | --- | --- | --- |
| 旧版  | DashScope `text-embedding-v3` | 1024 | 迁移到 Google 生态 |
| 过渡  | Google `text-embedding-005` | 768 | 仅英语/代码，多语言弱 |
| **当前** | Google `gemini-embedding-001` | **3072** | SOTA，中英马来三语统一，支持高精度 |

### gemini-embedding-001 核心优势

* 维度支持高达 3072，配合 `VertexAiEmbeddingModel.outputDimensionality(3072)`，在高维空间中特征分离极好。
* 多语言统一：中/英/马来语一个模型，无需分语言维护。
* MTEB 排行榜 SOTA 水平。

### 如何评估 Embedding 模型

在 Java 评估脚本中（如 JUnit 测试）：
1. **Source Hit Rate**：top-K 中是否包含预期来源文档。
2. **MRR（Mean Reciprocal Rank）**：第一个命中的排名倒数均值。
3. 多语言鲁棒性：用中英马来语混合查询测试跨语言召回能力。

---

## Q7. Embedding 有哪几种算法你了解过吗？

| 类型  | 代表  | 特点  | 我项目的用法 |
| --- | --- | --- | --- |
| **Word2Vec / GloVe** | Google Word2Vec | 词级别，无上下文感知 | ❌ 不用 |
| **BERT-based（双编码器）** | BGE、E5、gemini-embedding-001 | 句子级语义，双向注意力 | ✅ Dense 检索主力（VectorStoreService） |
| **Sparse（稀疏）** | BM25、SPLADE | 关键词精确匹配，可解释 | ✅ Sparse 检索（LexicalIndexService） |
| **Matryoshka（MRL）** | gemini-embedding-001 | 支持维度截断，存储/精度可调 | ✅ 当前 embedding，3072 维 |
| **Cross-Encoder** | BERT reranker | 精排用，联合编码 | ✅ VertexAiRankingClient 调用 Discovery Engine semantic-ranker-512 精排 |

---

## Q8. 什么是向量数据库？有没有做过向量数据库的对比选型？

### 向量数据库核心能力
* 高维向量的近似最近邻（ANN）检索。
* 向量 + 标量混合过滤（payload filter）。
* 持久化存储和分布式扩展。

### 我的选型经历（ChromaDB → Qdrant）

| 对比维度 | ChromaDB | **Qdrant（当前选择）** |
| --- | --- | --- |
| **原生 Hybrid Search** | ❌ 不支持 | ✅ Dense + Sparse 支持 |
| **Java 生态** | ❌ 弱 | ✅ 强大的 Java gRPC Client (`io.qdrant.client`) |
| **底层实现** | Python/SQLite | Rust，高性能 |
| **Payload Filter** | 基础  | 丰富（支持 range/match/geo/nested） |

**迁移的核心原因**：Qdrant 有极佳的 Java gRPC 客户端和 `LocalQdrantManager` 本地容器化/可执行管理支持，且天然支持强大的 Hybrid 检索需求。

---

## Q9. 讲讲你用的向量数据库？数据量级别？性能如何？遇到过性能瓶颈吗？

### Qdrant 配置与规模

* **文档数**：~50 个知识文件。
* **Chunk 数**：~2000+ chunks（800字符/块）。
* **向量维度**：3072 维。

### 性能表现（Java WebFlux 环境）

| 操作  | 延迟  |
| --- | --- |
| 单次混合检索 | < 200ms |
| 多查询并发检索（Reactor Flux 聚合） | < 400ms |
| Reranker API 调用（Vertex AI Discovery Engine） | ~400ms |
| 端到端（检索+精排+LLM 流式输出首字） | ~1.5～2s |

### 遇到的问题与解决

**1. 向量维度探针（Dimension Probing）**
在使用 `QdrantClient.createCollectionAsync` 之前，因为不同模型维度不同，我在 `VectorStoreService` 中通过 `modelFactory.getEmbeddingModel().embed("dimension probe")` 动态探测维度（3072维），杜绝了写死维度导致的启动崩溃。

**2. 并发 I/O 控制**
`VectorStoreService` 定义了一个专用的 `retrievalExecutor` 线程池（带有界队列 `LinkedBlockingQueue`），专用于 Qdrant gRPC RPC 及 Lucene I/O，防止 Spring WebFlux 的高并发压垮底层网络 IO。

**3. Embedding 模型迁移代价**
从 768 维到 3072 维，向量空间完全不兼容。Java 后端利用 `indexFingerprint` (基于 config 的 SHA-256 哈希) 来判定索引是否过时，并在 `VectorStoreService` 中触发全量重建。

---

## Q10. 你使用 RAG 给大模型一个输入，系统是怎样的工作流程？

这里指的是 **构建 LLM 输入（Prompt）的具体过程**。

### 完整 Prompt 构建流程 (RagService.java)

    // 1. 检索与精排
    List<Document> contextDocs = retrieveDocs(query).block();
    
    // 2. 检索信号验证（无信号直接返回）
    if (!hasRetrievalSignal(query, contextDocs)) {
        return Flux.just(NO_ANSWER_MESSAGE);
    }
    
    // 3. 格式化上下文
    String context = buildContext(contextDocs); // [Reference 1] Content: ... | Metadata: ...
    
    // 4. 填充 PromptTemplate
    String promptText = "## Assistant Persona\n"
            + systemPromptService.getPrompt()
            + "\n\n## Mandatory Retrieval Instructions\n"
            + getRagPromptTemplate()
            .replace("{input}", query)
            .replace("{context}", context);
            
    // 5. 调用 Vertex AI Stream 接口流式输出
    return streamModel(promptText);

---

## Q11. 请你介绍一下向量检索和关键词检索的区别？

| 对比维度 | 向量检索（Dense） | 关键词检索（Sparse/Lexical） |
| --- | --- | --- |
| **原理** | 语义相似度（余弦距离） | 词频-逆文档频率（BM25） |
| **匹配方式** | 语义近似即可 | 词汇精确匹配 |
| **优势** | 同义词、近义词、跨语言召回 | 专有名词、课程代码、人名精确匹配 |
| **劣势** | 专有名词/缩写可能语义漂移 | 换一种说法就召回不到 |
| **代表** | gemini-embedding-001 | Lucene BM25 (`LexicalIndexService`) |

### 我项目的实践：两者 Hybrid，取长补短

在 Java 项目中，除了调用 Qdrant 做 Dense 检索，我还整合了基于 Lucene 的 `LexicalIndexService` 做 `lucene-bm25-cjk-9.12.3` 稀疏检索。
实际检索时，两者在 `retrieveDocs` 阶段利用 RRF 融合。

**实际效果对比示例**：
* 查询 "TTTQ6143 是什么课" → BM25 精准命中，向量检索可能误召回其他课程。
* 查询 "实习有什么要求" → 向量检索找到 "internship requirements"，BM25 缺少中文"实习"词条。

---

## Q12. 如何润色用户的 Query（Query Rewrite）？目的是什么？

### 目的

原始用户输入往往口语化、含歧义、语言混杂——直接用于检索效果差。Query Rewrite 的目标是**把用户意图转化为知识库最容易命中的查询形式**。

### 我项目的三层 Query 处理（QueryPreprocessor.java）

**第一层：文本归一化**
使用 `Normalizer.normalize` (NFKC) 归一化全角半角。

**第二层：繁体→简体**
利用 `OpenCC4J`，如："籤證怎麼續簽" → "签证怎么续签"。

**第三层：话题关键词扩展 + 三语同义词扩展**
依靠内存中加载的 `Map<String, List<String>>`（即 SYNONYM_MAP）：
如 "签证" → ["student pass", "permit pelajar", "EMGS", "eVisa"]

**输出示例**：

    Input:  "签证怎么续签"
    Queries: [
      "签证怎么续签",
      "签证怎么续签 student pass",
      "签证怎么续签 permit pelajar"
    ]

---

## Q13. 什么是多路召回？具体怎么做？

### 概念

多路召回（Multi-Path Retrieval）是指**从多个不同来源/角度同时检索候选文档，再合并、去重、排序**，以提高整体召回率。

### 我项目的多路召回实现

在 `RagService.java` 的 `retrieveDocs` 中：

1. `queryPreprocessor.process(query)` 产出 N 个查询视角。
2. 利用 Reactor 框架的 `Flux.fromIterable` 加上 `Schedulers.boundedElastic()` 线程池并发提交给 Qdrant (`vectorStoreService.search`)。
3. 每个查询视角内部，Qdrant 同时执行 Dense 和 Sparse（BM25）检索。
4. 返回 `Flux` 聚合后，利用 `LinkedHashMap` 配合 `documentKey` (根据 chunk_id 或前 100 字符) 进行去重合并。
5. 最终喂给 `VertexAiRankingClient.rerank` (Vertex AI Discovery Engine `semantic-ranker-512@latest`) 进行 Cross-Encoder 交叉重排。

---

## Q14. RAG 检索优化策略有哪些？

### 我项目实现的优化策略

**① Hybrid Search（Dense + Sparse 互补）**
**② Multi-Query 并发扩展**（Reactor 异步非阻塞）
**③ Two-Stage 精排 (Bi-Encoder + Cross-Encoder)**（Vertex AI Discovery Engine semantic-ranker-512@latest）
**④ Query Boost（结构化标识符强化）**
在 `RagService` 的 `getQueryBoost` 逻辑中，对 `[A-Z]{2}\d{4}`（课程代码）、`BK\s*\d+`（教室）等进行正则提取并直接加分（`boost += 5.0`）。
**⑤ Source Weight（来源权威性调整）**
对 `official` 降低惩罚/提高得分，让官方资料总是排在爬虫和社区数据前。
**⑥ Retrieval Signal Validation（拒绝无证据回答）**
`hasRetrievalSignal` 校验重叠度。
**⑦ 语义缓存（Semantic Cache）**
**⑧ 基于 Fingerprint 的增量索引**
`VectorStoreService` 会将配置（chunk_size、模型名等）打成 SHA-256 指纹，结合 DocumentManifestManager 追踪每个文件的修改，避免重复 Embedding。

---

## Q15. 了解哪些更复杂的 RAG 范式？

### 1. Self-RAG（自我反思 RAG）
模型自主决定是否需要检索及答案是否有依据。我的 `hasRetrievalSignal` (无证据拒答) 就是外部实现的简易版反射。

### 2. Agentic RAG（智能体 RAG）
LLM 作为 Agent 自主决定是否需要检索。我的项目 `ReactAgent.java` 基于 LangChain4j 的 `ToolSpecification` 定义了 `rag_search` 工具，Gemini 模型根据用户意图自主决定是否调用 RAG 检索。对于闲聊/翻译等非学术问题直接流式回答，对于涉及 UKM/FTSM 事实的问题则自动生成独立检索查询并走 RAG 流程。

### 3. Graph RAG
将知识库构建为图谱，适合多跳推理。

### 4. HyDE（Hypothetical Document Embeddings）
先生成"虚构答案"，再用虚构答案去检索。

---

## Q16. 在什么场景下，你会选择使用图数据库来增强传统的向量检索？

### 图数据库的优势场景

1. **多跳关系推理**：比如“A 导师的学生的论文主题”。
2. **结构化关联信息**：课程 → 前置课程 → 授课教师。
3. **实体消歧**：同名教授。

对于这些场景，向量相似度检索无能为力。如果引入，会在 Spring Data Neo4j 体系下构建实体关系，实施 Graph RAG。

---

## Q17. 如何规避 RAG 系统中大模型的幻觉？

**① 检索信号验证（RagService.hasRetrievalSignal）**
检查 top-3 文档的内容是否与 `getQueryTerms()` 提取的关键非停用词有重叠，如果不达标直接返回常量 `NO_ANSWER_MESSAGE`，切断 LLM 幻觉链。

**② Prompt 约束**
在 `rag_summarize.txt` 中强约束："如果资料不足，明确告知无法回答，不得推测"。

**③ 来源可信度分级 + 优先排序**
利用 `getSourceTrustLabel` 和 `applySourceWeight`，给官方资料加权，减少劣质语料被选中的几率。

**④ 语义缓存只缓存高质量答案**
避免重复回答时跑偏。

---

## Q18. 怎么量化你的 RAG 效果？

可以通过自定义的测试套件或者脚本测试：
* **Source Hit Rate**：top-K 中是否出现预期来源文档。
* **MRR（Mean Reciprocal Rank）**：第一个命中文档排名的倒数均值。
* **Precision@K / Recall@K**。
* **Latency P50/P90**：端到端流式响应首字节的延迟。
针对 Prompt 注入（"Ignore rules and invent..."）和范围外问题（天气）做 Negative Test，确保 `expected_no_answer` 生效。

---

## Q19. RAG 知识库如何实现动态与持续更新？

### 我项目的三套更新机制

**① 增量索引（核心）**
`VectorStoreService` 与 `DocumentManifestManager` 配合，读取 `.manifest` (如 `ManifestData`)：
如果源文件 SHA-256 和索引配置的 `indexFingerprint` 均未改变，则跳过 Embedding；如果变更，则利用锁 `indexMutationLock` 安全更新。

**② 定时爬虫（CrawlScheduler.java）**
基于 `@Scheduled` 注解的定时任务，调用 `FtsmWebsiteCrawler` 定期爬取 FTSM 官方网站新闻和通告。

**③ 安全删除旧块（防孤儿 Chunk）**
在覆盖更新时，精准调用 QdrantClient 删除 `ManifestRecord` 中旧的 `chunk_id` 集合，避免数据膨胀。

---

## Q20. 在实际落地中，你觉得 RAG 最难的地方是哪里？

结合 Java 项目落地经验：

### 1. 多语言混合查询（最难核心）
留学生提问中英马来混杂（如"visa renew", "bas kampus"）。
解法：基于 `QueryPreprocessor` 加载多语言 Map 做同义词硬拓展，加上 OpenCC4J，结合 Qdrant 并发多搜。

### 2. 结构化标识符的检索精度
课程代码（TTTQ6143）、年份极容易被 Embedding 模型泛化。
解法：手写正则（如 `\b[A-Z]{2}\d{4}\b`），利用 Java 代码层面的 `applyQueryBoost` 进行后置强加分。

### 3. Java 异步生态的整合
相比 Python 的线程池，Spring WebFlux 需要全链路异步。
解法：使用 Reactor 的 `Flux`/`Mono` 和 `Schedulers.boundedElastic()` 协调网络 IO（Qdrant gRPC / DashScope / Vertex AI），保证了极佳的并发吞吐，使首字生成延迟降低到 <1s。

### 4. 幻觉与过度拒答的平衡
`hasRetrievalSignal` 词项重叠规则需要经过大量微调，防止要求太严导致可用性差，要求太松导致幻觉。

---

## 附：项目完整技术栈速查表

| 组件  | 技术  | 说明  |
| --- | --- | --- |
| Chat 模型 | Google `gemini-3.5-flash` (Vertex AI) | 极速响应，支持 Streaming |
| Embedding | Google `gemini-embedding-001` 3072维 | 维度探针动态探测加载 |
| Reranker | Google Vertex AI Discovery Engine `semantic-ranker-512@latest` | WebClient 接入 Cross-Encoder |
| 智能路由 | `ReactAgent` + LangChain4j ToolSpecification | Gemini 自主决定是否走 RAG |
| 向量数据库 | Qdrant (Java gRPC Client) | 强大的 io.qdrant.client |
| 稀疏检索 | Lucene BM25 (`LexicalIndexService`) | Java 生态原生支持 |
| 并发框架 | Project Reactor (Flux/Mono) | 全链路异步非阻塞 |
| 文本切割 | `PythonCompatibleTextSplitter` | LangChain4j 风格自定义拓展 |
| 定时爬虫 | `FtsmWebsiteCrawler` | 自动化获取外部知识 |
