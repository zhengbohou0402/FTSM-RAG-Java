import { useState } from "react";
import { Modal, Input, Button, Typography, Radio, Space, message } from "antd";
import { EyeOutlined, EyeInvisibleOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import { useSettings } from "../hooks/useSettings";
import setupIcon from "../assets/api-key-setup-icon.png";

const { Text, Paragraph } = Typography;

const INTL_BASE_DEFAULT = "https://dashscope-intl.aliyuncs.com/api/v1";

interface Props {
  open: boolean;
  onSuccess: () => void;
}

export default function ApiKeySetupModal({ open, onSuccess }: Props) {
  const { saving, save } = useSettings();
  const [apiKey, setApiKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [region, setRegion] = useState<"china" | "intl">("china");
  const [intlBase, setIntlBase] = useState(INTL_BASE_DEFAULT);

  const handleSave = async () => {
    const key = apiKey.trim();
    if (!key) {
      message.error("Please enter your DashScope API key.");
      return;
    }
    const baseUrl = region === "intl" ? (intlBase.trim() || INTL_BASE_DEFAULT) : "";
    const ok = await save({
      dashscope_api_key: key,
      dashscope_base_url: baseUrl,
      chat_model_name: "qwen-turbo",
    });
    if (ok) {
      message.success("Saved. You’re ready to go.");
      setApiKey("");
      onSuccess();
    } else {
      message.error("Could not save settings. Check your connection and try again.");
    }
  };

  return (
    <Modal
      title={
        <div style={{ textAlign: "center", padding: "4px 0 0" }}>
          <img
            src={setupIcon}
            alt="FTSM-RAG"
            width={72}
            height={72}
            style={{
              borderRadius: 16,
              display: "block",
              margin: "0 auto 14px",
              boxShadow: "0 8px 24px rgba(46, 109, 164, 0.22)",
            }}
          />
          <span style={{ fontSize: 17, fontWeight: 600 }}>Configure DashScope API Key</span>
        </div>
      }
      open={open}
      closable={false}
      keyboard={false}
      footer={null}
      width={480}
      centered
      destroyOnHidden
      mask={{ closable: false }}
    >
      <Paragraph type="secondary" style={{ marginBottom: 16, textAlign: "center" }}>
        Enter your Alibaba Cloud Model Studio (DashScope) API key for chat and retrieval. It is stored only in
        your local <code style={{ fontSize: 12 }}>.env</code> file on this machine.
      </Paragraph>
      <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
        <div>
          <Text strong style={{ display: "block", marginBottom: 8 }}>
            API key
          </Text>
          <Input
            type={showKey ? "text" : "password"}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder="sk-xxxxxxxx"
            autoComplete="off"
            suffix={
              <Button
                type="text"
                size="small"
                icon={showKey ? <EyeInvisibleOutlined /> : <EyeOutlined />}
                onClick={() => setShowKey(!showKey)}
              />
            }
          />
        </div>
        <div>
          <Text strong style={{ display: "block", marginBottom: 8 }}>
            Region
          </Text>
          <Radio.Group
            value={region}
            onChange={(e) => setRegion(e.target.value)}
            buttonStyle="solid"
          >
            <Radio.Button value="china">China</Radio.Button>
            <Radio.Button value="intl">International</Radio.Button>
          </Radio.Group>
        </div>
        {region === "intl" && (
          <div>
            <Text strong style={{ display: "block", marginBottom: 8 }}>
              Base URL (international)
            </Text>
            <Input
              value={intlBase}
              onChange={(e) => setIntlBase(e.target.value)}
              placeholder={INTL_BASE_DEFAULT}
            />
          </div>
        )}
        <Button type="primary" block onClick={handleSave} loading={saving}>
          Save and continue
        </Button>
        <Text type="secondary" style={{ fontSize: 12, display: "block", textAlign: "center" }}>
          To pick a model, verify your key, and more,{" "}
          <Link to="/settings">open full settings</Link>
        </Text>
      </Space>
    </Modal>
  );
}
