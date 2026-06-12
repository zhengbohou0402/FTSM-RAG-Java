import { useEffect, useState, useMemo } from "react";
import { Input, Button, Select, Typography, Space, message, Tag, Radio } from "antd";
import {
  EyeInvisibleOutlined,
  EyeOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useSettings } from "../hooks/useSettings";

const { Text } = Typography;

const BUILTIN_MODELS: string[] = ["qwen-turbo"];

export default function Settings() {
  const { settings: saved, models, saving, load, loadModels, save } = useSettings();
  const navigate = useNavigate();

  const [apiKey, setApiKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [region, setRegion] = useState<"china" | "intl">("china");
  const [baseUrl, setBaseUrl] = useState("");
  const [selectedModel, setSelectedModel] = useState("");
  const [customModel, setCustomModel] = useState("");
  const [useCustomModel, setUseCustomModel] = useState(false);
  const [searchText, setSearchText] = useState("");
  const [keyStatus, setKeyStatus] = useState<{ valid?: boolean | null; error?: string }>({});
  const [initialized, setInitialized] = useState(false);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!saved || initialized) return;

    const timer = window.setTimeout(() => {
      setApiKey(saved.dashscope_api_key || "");
      const isIntl = (saved.dashscope_base_url || "").includes("dashscope-intl");
      setRegion(isIntl ? "intl" : "china");
      setBaseUrl(isIntl ? saved.dashscope_base_url : "");
      const savedModel = saved.chat_model_name || "";
      if (savedModel && !BUILTIN_MODELS.includes(savedModel)) {
        setUseCustomModel(true);
        setCustomModel(savedModel);
        setSelectedModel("__custom__");
      } else {
        setSelectedModel(savedModel);
      }
      setInitialized(true);
    }, 0);

    return () => window.clearTimeout(timer);
  }, [saved, initialized]);

  const modelOptions = useMemo(() => {
    const apiModels = models?.models || [];
    const merged = [...new Set([...BUILTIN_MODELS, ...apiModels])];
    return [
      ...merged.map((m) => ({ value: m, label: m })),
      { value: "__custom__", label: "Custom model..." },
    ];
  }, [models]);

  const filteredOptions = useMemo(() => {
    if (!searchText) return modelOptions;
    const filtered = modelOptions.filter((opt) =>
      opt.label.toLowerCase().includes(searchText.toLowerCase())
    );
    const exactMatch = modelOptions.some(
      (opt) => opt.value.toLowerCase() === searchText.toLowerCase()
    );
    if (!exactMatch && searchText.trim()) {
      filtered.push({ value: searchText.trim(), label: `Use "${searchText.trim()}"` });
    }
    return filtered;
  }, [searchText, modelOptions]);

  const handleVerify = async () => {
    const data = await loadModels(region);
    if (data) {
      setKeyStatus({ valid: data.key_valid, error: data.key_error });
      if (data.key_valid) {
        message.success(`API key valid. ${data.models.length} models synced.`);
      }
    }
  };

  const handleModelChange = (val: string) => {
    if (val === "__custom__") {
      setUseCustomModel(true);
      setSelectedModel("__custom__");
      setSearchText("");
    } else {
      setUseCustomModel(false);
      setSelectedModel(val);
      setSearchText("");
    }
  };

  const handleRegionChange = (nextRegion: "china" | "intl") => {
    setRegion(nextRegion);
    setBaseUrl(nextRegion === "intl" ? "https://dashscope-intl.aliyuncs.com/api/v1" : "");
  };

  const handleSave = async () => {
    const isMasked = apiKey.startsWith("sk-****") && apiKey.length <= 12;
    const keyToSend = isMasked ? "" : apiKey.trim();
    if (!keyToSend && !isMasked) {
      message.error("API key cannot be empty.");
      return;
    }

    const modelToSave = useCustomModel ? customModel.trim() : selectedModel;
    if (!modelToSave) {
      message.error("Please select or enter a model.");
      return;
    }

    const success = await save({
      dashscope_api_key: keyToSend,
      dashscope_base_url: baseUrl,
      chat_model_name: modelToSave,
    });

    if (success) {
      message.success("Settings saved. Redirecting...");
      setTimeout(() => navigate("/"), 900);
    } else {
      message.error("Failed to save settings.");
    }
  };

  return (
    <div className="admin-shell settings-shell">
      <div className="admin-header settings-header">
        <div>
          <Text type="secondary" className="page-kicker">FTSM GPT</Text>
          <h1>Settings</h1>
          <p>Configure the DashScope API key, service region, and Qwen chat model.</p>
        </div>

      </div>

      <div className="settings-layout">
        <section className="admin-panel settings-card">
          <Space orientation="vertical" size="large" className="settings-stack">
            <section>
              <div className="panel-title-row">
                <Text strong>DashScope API Key</Text>
                <Tag color="red">Required</Tag>
              </div>
              <Text type="secondary" className="field-help">
                Used for the chat model, embeddings, and image text extraction.
              </Text>
              <Input
                type={showKey ? "text" : "password"}
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder="sk-xxxxxxxxxxxxxxxx"
                suffix={
                  <Button
                    type="text"
                    size="small"
                    icon={showKey ? <EyeInvisibleOutlined /> : <EyeOutlined />}
                    onClick={() => setShowKey(!showKey)}
                  />
                }
              />
            </section>

            <section>
              <Text strong className="field-label">Service Region</Text>
              <Radio.Group
                className="settings-region-group"
                value={region}
                onChange={(e) => handleRegionChange(e.target.value)}
                optionType="button"
                buttonStyle="solid"
              >
                <Radio.Button value="china">China</Radio.Button>
                <Radio.Button value="intl">International</Radio.Button>
              </Radio.Group>
              <Text type="secondary" className="field-help">
                China uses the default DashScope endpoint. International uses DashScope Intl.
              </Text>
            </section>

            <section>
              <Text strong className="field-label">Chat Model</Text>
              <Space.Compact className="settings-model-row">
                {useCustomModel ? (
                  <Input
                    value={customModel}
                    onChange={(e) => setCustomModel(e.target.value)}
                    placeholder="Enter model name, e.g. qwen-plus"
                    onBlur={() => {
                      if (!customModel.trim()) {
                        setUseCustomModel(false);
                        setSelectedModel("");
                      }
                    }}
                  />
                ) : (
                  <Select
                    showSearch
                    value={selectedModel || undefined}
                    onChange={handleModelChange}
                    onSearch={setSearchText}
                    onBlur={() => setSearchText("")}
                    placeholder="Select or type a model"
                    options={filteredOptions}
                    filterOption={false}
                    notFoundContent={searchText ? `Type Enter to use "${searchText}"` : "No models found"}
                  />
                )}
                <Button icon={<ReloadOutlined />} onClick={handleVerify}>
                  Verify
                </Button>
              </Space.Compact>
              {useCustomModel && (
                <Button
                  type="link"
                  size="small"
                  onClick={() => { setUseCustomModel(false); setCustomModel(""); }}
                  className="settings-link-button"
                >
                  Back to preset models
                </Button>
              )}
              {keyStatus.valid === true && (
                <Text type="success" className="field-help">API key valid. Models synced.</Text>
              )}
              {keyStatus.valid === false && (
                <Text type="danger" className="field-help">{keyStatus.error || "Invalid API key."}</Text>
              )}
            </section>

            <Button type="primary" onClick={handleSave} loading={saving} block>
              Save and apply
            </Button>
          </Space>
        </section>

        <aside className="admin-panel settings-side-panel">
          <Text strong>Current Setup</Text>
          <div className="info-grid settings-info-grid">
            <div><Text type="secondary">Region</Text><strong>{region === "intl" ? "International" : "China"}</strong></div>
            <div><Text type="secondary">Endpoint</Text><strong>{baseUrl || "Default"}</strong></div>
            <div><Text type="secondary">Model</Text><strong>{useCustomModel ? customModel || "-" : selectedModel || "-"}</strong></div>
            <div><Text type="secondary">Synced Models</Text><strong>{models?.models.length ?? 0}</strong></div>
          </div>
        </aside>
      </div>
    </div>
  );
}
