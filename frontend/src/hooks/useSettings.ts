import { useState, useCallback } from "react";
import { api } from "../api/client";
import type { Settings, ModelsResponse } from "../api/client";

export function useSettings() {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [models, setModels] = useState<ModelsResponse | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await api.settings.get();
      setSettings(data);
    } catch {
      // ignore
    }
  }, []);

  const loadModels = useCallback(async (region?: string) => {
    try {
      const data = await api.models(region);
      setModels(data);
      return data;
    } catch {
      return null;
    }
  }, []);

  const save = useCallback(
    async (updates: { dashscope_api_key: string; dashscope_base_url: string; chat_model_name: string }) => {
      setSaving(true);
      try {
        await api.settings.save(updates);
        return true;
      } catch {
        return false;
      } finally {
        setSaving(false);
      }
    },
    []
  );

  return { settings, models, saving, load, loadModels, save };
}
