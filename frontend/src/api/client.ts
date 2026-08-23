import { invoke } from "@tauri-apps/api/core";

let _baseUrl: string | null = null;

async function getBaseUrl(): Promise<string> {
  if (_baseUrl) return _baseUrl;

  try {
    // Desktop mode: ask the Tauri host which backend port it started.
    _baseUrl = await invoke<string>("get_server_url");
  } catch {
    const location = window.location;
    const isViteDevServer = ["5173", "5174"].includes(location.port);
    _baseUrl = isViteDevServer ? "http://127.0.0.1:8000" : location.origin;
  }
  return _baseUrl;
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const base = await getBaseUrl();
  const res = await fetch(`${base}${path}`, {
    headers: { "Content-Type": "application/json", ...options?.headers },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { detail?: string }).detail || res.statusText);
  }
  return res.json();
}

export interface Conversation {
  id: string;
  title: string;
  updated_at: number;
  messages?: Message[];
}

export interface Message {
  role: "user" | "assistant";
  content: string;
  sources?: Source[];
  thinking?: string[];
}

export interface Source {
  file: string;
  chunk_index: number;
  excerpt: string;
  source_type?: string;
}

export interface Document {
  name: string;
  size: number;
  modified: number;
  indexed?: boolean;
  stale?: boolean;
  chunks?: number;
  source_type?: string | null;
  source_trust_label?: string | null;
  indexed_at?: string | null;
  covered_by?: string | null;
  index_note?: string | null;
}

export interface TrainingStatus {
  running: boolean;
  pending: boolean;
  last_result: string | null;
  last_error: string | null;
}

export interface KnowledgeStats {
  document_count: number;
  manifest_records: number;
  total_chunks: number;
  last_indexed: string | null;
  cache_entries: number;
  index_version: number;
  index_updated_at: string | null;
  index_last_error: string | null;
  source_type_counts: Record<string, number>;
  dense_points: number;
  lexical_documents: number;
  manifest_chunks: number;
  qdrant_collection: string;
  lexical_generation: string | null;
  index_generation: string | null;
  index_consistent: boolean;
}

export interface CacheStats {
  hit_count: number;
  miss_count: number;
  hit_rate: number;
  size: number;
  valid: number;
  threshold: number;
}

export interface SchedulerStatus {
  enabled: boolean;
  manual_available: boolean;
  interval_hours: number;
  max_pages: number;
  last_run: string | null;
  next_run: string | null;
  running: boolean;
  mode: "scheduled" | "manual" | null;
  phase: "idle" | "crawling" | "indexing";
  last_success: string | null;
  last_attempt: string | null;
  last_error: string | null;
  last_output_file: string | null;
  pages_crawled: number;
  pages_visited: number;
  pages_skipped: number;
  pages_failed: number;
  crawler_engine: string | null;
  current_url: string | null;
  browser_enabled: boolean;
  browser_auto_download: boolean;
  static_fallback: boolean;
  static_tls_fallback: boolean;
  thread_alive: boolean;
}

export interface Settings {
  dashscope_api_key: string;
  dashscope_base_url: string;
  chat_model_name: string;
}

export interface ModelsResponse {
  models: string[];
  key_valid: boolean | null;
  key_error: string;
  region: string;
}

export interface DocumentChunk {
  chunk_id: string | null;
  chunk_index: number;
  text: string;
  source_type: string | null;
  source_trust_label: string | null;
}

export async function streamChat(
  message: string,
  conversationId: string | null,
  signal?: AbortSignal
): Promise<{ reader: ReadableStreamDefaultReader<Uint8Array>; conversationId: string }> {
  const base = await getBaseUrl();
  const res = await fetch(`${base}/api/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message, conversation_id: conversationId }),
    signal,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { detail?: string }).detail || res.statusText);
  }
  const cid = res.headers.get("X-Conversation-Id") || "";
  return { reader: res.body!.getReader(), conversationId: cid };
}

export const api = {
  health: () => request<{ status: string }>("/api/health"),
  configStatus: () => request<{ dashscope_configured: boolean }>("/api/config/status"),
  chat: streamChat,

  // Conversations
  conversations: {
    list: () => request<{ items: Conversation[] }>("/api/conversations"),
    create: () => request<Conversation>("/api/conversations", { method: "POST" }),
    get: (id: string) => request<Conversation>(`/api/conversations/${id}`),
    delete: (id: string) => request<{ ok: boolean }>(`/api/conversations/${id}`, { method: "DELETE" }),
    deleteAll: () => request<{ ok: boolean }>("/api/conversations", { method: "DELETE" }),
  },

  // Settings
  settings: {
    get: () => request<Settings>("/api/settings"),
    save: (data: { dashscope_api_key: string; dashscope_base_url: string; chat_model_name: string }) =>
      request<{ ok: boolean }>("/api/settings", { method: "POST", body: JSON.stringify(data) }),
  },
  prompt: {
    get: () => request<{ system_prompt: string }>("/api/prompt"),
    save: (systemPrompt: string) =>
      request<{ ok: boolean }>("/api/prompt", {
        method: "POST",
        body: JSON.stringify({ system_prompt: systemPrompt }),
      }),
  },
  models: (region?: string) =>
    request<ModelsResponse>(`/api/models${region ? `?list_region=${region}` : ""}`),

  // Documents
  documents: {
    list: () => request<{ documents: Document[] }>("/api/documents"),
    upload: async (files: FileList) => {
      const base = await getBaseUrl();
      const fd = new FormData();
      for (const f of Array.from(files)) fd.append("files", f);
      const response = await fetch(`${base}/api/upload`, { method: "POST", body: fd });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error((body as { detail?: string }).detail || response.statusText);
      }
      return response.json() as Promise<{
        saved: string[];
        errors: Array<{ name: string; error: string }>;
        training_started: boolean;
      }>;
    },
    delete: (filename: string) =>
      request<{ ok: boolean }>(`/api/documents/${encodeURIComponent(filename)}`, { method: "DELETE" }),
    chunks: (filename: string) =>
      request<{ filename: string; chunks: DocumentChunk[]; count: number }>(
        `/api/document-chunks?filename=${encodeURIComponent(filename)}`
      ),
  },

  // Training
  training: {
    status: () => request<TrainingStatus>("/api/training/status"),
    start: () => request<{ started: boolean; message: string }>("/api/training/start", { method: "POST" }),
  },

  // Stats
  knowledge: {
    stats: () => request<KnowledgeStats>("/api/knowledge/stats"),
    update: (reindex = true) => request<{ started: boolean; message: string; max_pages: number; reindex: boolean }>(
      `/api/knowledge/update?reindex=${reindex ? "true" : "false"}`,
      { method: "POST" }
    ),
  },
  cache: { stats: () => request<CacheStats>("/api/cache/stats") },
  scheduler: { status: () => request<SchedulerStatus>("/api/scheduler/status") },
};
