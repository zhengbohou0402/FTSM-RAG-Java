import { useState, useCallback } from "react";
import { api } from "../api/client";
import type { Conversation } from "../api/client";

export function useConversations() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.conversations.list();
      setConversations(data.items);
    } finally {
      setLoading(false);
    }
  }, []);

  const create = useCallback(async () => {
    const conv = await api.conversations.create();
    await refresh();
    return conv;
  }, [refresh]);

  const remove = useCallback(
    async (id: string) => {
      await api.conversations.delete(id);
      await refresh();
    },
    [refresh]
  );

  const removeAll = useCallback(async () => {
    await api.conversations.deleteAll();
    await refresh();
  }, [refresh]);

  return { conversations, loading, refresh, create, remove, removeAll };
}
