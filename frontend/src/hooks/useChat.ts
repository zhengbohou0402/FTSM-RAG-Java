import { useState, useCallback, useRef } from "react";
import { api } from "../api/client";
import type { Message, Source } from "../api/client";

const THINK_RE = /__THINK__([\s\S]*?)__ENDTHINK__/g;
const SOURCES_RE = /\n\nSources:\n([\s\S]+)$/;
const SOURCE_LINE_RE = /^- \[(\d+)\] (.+?)(?: \[(.+?)\])?(?:, chunk (\d+))?: (.+)$/;

function parseThinking(raw: string): { thinking: string[]; clean: string } {
  const thinking: string[] = [];
  const clean = raw.replace(THINK_RE, (_match, inner) => {
    thinking.push(inner.trim());
    return "";
  });
  return { thinking, clean: clean.trim() };
}

function parseSources(raw: string): { content: string; sources?: Source[] } {
  const match = raw.match(SOURCES_RE);
  if (!match) return { content: raw.trim() };

  const sourceLines = match[1]
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.startsWith("- ["));

  const sources = sourceLines
    .map((line): Source | null => {
      const parsed = line.match(SOURCE_LINE_RE);
      if (!parsed) return null;
      const source: Source = {
        file: parsed[2].trim(),
        chunk_index: Number(parsed[4] ?? 0),
        excerpt: parsed[5].trim(),
      };
      if (parsed[3]?.trim()) {
        source.source_type = parsed[3].trim();
      }
      return source;
    })
    .filter((source): source is Source => source !== null);

  if (!sources.length) return { content: raw.trim() };
  return { content: raw.slice(0, raw.length - match[0].length).trim(), sources };
}

function parseAssistantMessage(raw: string): Message {
  const parsedThinking = parseThinking(raw);
  const parsedSources = parseSources(parsedThinking.clean);
  return {
    role: "assistant",
    content: parsedSources.content,
    thinking: parsedThinking.thinking.length > 0 ? parsedThinking.thinking : undefined,
    sources: parsedSources.sources,
  };
}

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const operationRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const cancelActiveOperation = useCallback(() => {
    operationRef.current += 1;
    abortRef.current?.abort();
    abortRef.current = null;
    setStreaming(false);
  }, []);

  const send = useCallback(
    async (text: string) => {
      cancelActiveOperation();
      const operation = operationRef.current;
      const controller = new AbortController();
      abortRef.current = controller;

      const userMsg: Message = { role: "user", content: text };
      setMessages((prev) => [...prev, userMsg]);
      setStreaming(true);

      let rawContent = "";
      const assistantMsg: Message = { role: "assistant", content: "", thinking: [] };
      setMessages((prev) => [...prev, assistantMsg]);

      try {
        const { reader, conversationId: cid } = await api.chat(
          text,
          conversationId,
          controller.signal
        );
        if (operation !== operationRef.current) return;
        setConversationId(cid);

        const decoder = new TextDecoder();
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          if (operation !== operationRef.current) {
            await reader.cancel();
            return;
          }
          rawContent += decoder.decode(value, { stream: true });
          const parsed = parseAssistantMessage(rawContent);
          setMessages((prev) => {
            if (operation !== operationRef.current || prev.length === 0) {
              return prev;
            }
            const copy = [...prev];
            copy[copy.length - 1] = parsed;
            return copy;
          });
        }
      } catch (err) {
        if (controller.signal.aborted || operation !== operationRef.current) {
          return;
        }
        setMessages((prev) => {
          if (operation !== operationRef.current || prev.length === 0) {
            return prev;
          }
          const copy = [...prev];
          copy[copy.length - 1] = {
            role: "assistant",
            content: `Error: ${err instanceof Error ? err.message : "Unknown error"}`,
          };
          return copy;
        });
      } finally {
        if (operation === operationRef.current) {
          abortRef.current = null;
          setStreaming(false);
        }
      }
    },
    [cancelActiveOperation, conversationId]
  );

  const clearMessages = useCallback(() => {
    cancelActiveOperation();
    setMessages([]);
    setConversationId(null);
  }, [cancelActiveOperation]);

  const loadConversation = useCallback(async (id: string) => {
    cancelActiveOperation();
    const operation = operationRef.current;
    try {
      const conv = await api.conversations.get(id);
      if (operation !== operationRef.current) return;
      setConversationId(conv.id);
      // Parse thinking blocks from loaded messages too
      const parsed = (conv.messages || []).map((msg) => {
        if (msg.role === "assistant" && !msg.thinking) {
          return parseAssistantMessage(msg.content);
        }
        return msg;
      });
      setMessages(parsed);
    } catch {
      // conversation may have been deleted
    }
  }, [cancelActiveOperation]);

  return { messages, streaming, conversationId, send, clearMessages, loadConversation, setConversationId };
}
