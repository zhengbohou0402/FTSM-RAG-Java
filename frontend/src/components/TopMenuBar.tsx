import { useCallback, useEffect, useState } from "react";
import { Dropdown, Modal, Typography, Space, message } from "antd";
import type { MenuProps } from "antd";
import {
  CodeOutlined,
  PlusOutlined,
  FileSearchOutlined,
  CloseOutlined,
  ClearOutlined,
  MessageOutlined,
  DashboardOutlined,
  SettingOutlined,
  BulbOutlined,
  SyncOutlined,
  PlayCircleOutlined,
  ReadOutlined,
  InfoCircleOutlined,
} from "@ant-design/icons";
import { useNavigate, useLocation } from "react-router-dom";
import { invoke } from "@tauri-apps/api/core";
import { api } from "../api/client";

const { Text, Link: AntLink } = Typography;

interface Props {
  isDark: boolean;
  onToggleTheme: () => void;
}

export default function TopMenuBar({ isDark, onToggleTheme }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const [aboutOpen, setAboutOpen] = useState(false);

  const handleNewChat = useCallback(() => {
    if (location.pathname !== "/") {
      navigate("/");
      window.setTimeout(() => {
        window.dispatchEvent(new CustomEvent("new-chat"));
      }, 100);
    } else {
      window.dispatchEvent(new CustomEvent("new-chat"));
    }
  }, [location.pathname, navigate]);

  const handleClearChat = useCallback(() => {
    if (location.pathname === "/") {
      window.dispatchEvent(new CustomEvent("clear-chat"));
    } else {
      message.warning("You must be on the chat page to clear messages.");
    }
  }, [location.pathname]);

  // Keyboard shortcuts
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key.toLowerCase() === "n") {
        e.preventDefault();
        handleNewChat();
      } else if (e.ctrlKey && e.key.toLowerCase() === "m") {
        e.preventDefault();
        navigate("/manage");
      } else if (e.ctrlKey && e.key.toLowerCase() === "k") {
        e.preventDefault();
        handleClearChat();
      } else if (e.ctrlKey && e.key.toLowerCase() === "t") {
        e.preventDefault();
        onToggleTheme();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [handleClearChat, handleNewChat, navigate, onToggleTheme]);

  const handleExit = async () => {
    try {
      await invoke("quit_application");
    } catch {
      message.info("Exit application triggered. In browser mode, please close this tab.");
    }
  };

  const handleTriggerIndexing = async () => {
    try {
      const res = await api.training.start();
      if (res.started) {
        message.success("Knowledge base indexing started in the background.");
      } else {
        message.info(res.message || "Indexing is already running.");
      }
    } catch (err) {
      message.error(`Failed to start indexing: ${err}`);
    }
  };

  const chatItems: MenuProps["items"] = [
    {
      key: "go-to-chat",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <MessageOutlined style={{ marginRight: 8 }} />
          <span>Chat Assistant</span>
        </div>
      ),
      onClick: () => navigate("/"),
    },
    {
      type: "divider",
    },
    {
      key: "new-chat",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <PlusOutlined style={{ marginRight: 8 }} />
          <span>New Chat</span>
          <span className="top-menu-shortcut">Ctrl+N</span>
        </div>
      ),
      onClick: handleNewChat,
    },
    {
      key: "clear-chat",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <ClearOutlined style={{ marginRight: 8 }} />
          <span>Clear Chat History</span>
          <span className="top-menu-shortcut">Ctrl+K</span>
        </div>
      ),
      onClick: handleClearChat,
      disabled: location.pathname !== "/",
    },
  ];

  const knowledgeItems: MenuProps["items"] = [
    {
      key: "manage-docs",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <FileSearchOutlined style={{ marginRight: 8 }} />
          <span>Manage Documents</span>
          <span className="top-menu-shortcut">Ctrl+M</span>
        </div>
      ),
      onClick: () => navigate("/manage"),
    },
    {
      key: "trigger-indexing",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <PlayCircleOutlined style={{ marginRight: 8 }} />
          <span>Start Knowledge Indexing</span>
        </div>
      ),
      onClick: handleTriggerIndexing,
    },
  ];

  const consoleItems: MenuProps["items"] = [
    {
      key: "dashboard",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <DashboardOutlined style={{ marginRight: 8 }} />
          <span>System Dashboard</span>
        </div>
      ),
      onClick: () => navigate("/dashboard"),
    },
    {
      key: "refresh",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <SyncOutlined style={{ marginRight: 8 }} />
          <span>Refresh Page</span>
        </div>
      ),
      onClick: () => window.location.reload(),
    },
  ];

  const settingsItems: MenuProps["items"] = [
    {
      key: "settings-page",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <SettingOutlined style={{ marginRight: 8 }} />
          <span>Application Settings</span>
        </div>
      ),
      onClick: () => navigate("/settings"),
    },
    {
      key: "system-prompts",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <MessageOutlined style={{ marginRight: 8 }} />
          <span>System Prompts</span>
        </div>
      ),
      onClick: () => navigate("/prompts"),
    },
    {
      key: "toggle-theme",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <BulbOutlined style={{ marginRight: 8 }} />
          <span>Toggle Dark/Light Theme</span>
          <span className="top-menu-shortcut">Ctrl+T</span>
        </div>
      ),
      onClick: onToggleTheme,
    },
    {
      type: "divider",
    },
    {
      key: "exit",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <CloseOutlined style={{ marginRight: 8 }} />
          <span>Exit Application</span>
        </div>
      ),
      onClick: handleExit,
    },
  ];

  const helpItems: MenuProps["items"] = [
    {
      key: "documentation",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <ReadOutlined style={{ marginRight: 8 }} />
          <span>Read Documentations</span>
        </div>
      ),
      onClick: () => window.open("https://github.com/zhengbohou0402/FTSM-RAG-Java", "_blank"),
    },
    {
      type: "divider",
    },
    {
      key: "about",
      label: (
        <div style={{ display: "flex", alignItems: "center" }}>
          <InfoCircleOutlined style={{ marginRight: 8 }} />
          <span>About FTSM-RAG</span>
        </div>
      ),
      onClick: () => setAboutOpen(true),
    },
  ];

  return (
    <div className="top-menu-bar">
      <div className="top-menu-left">
        <div className="top-menu-logo">
          <CodeOutlined style={{ color: isDark ? "#61afef" : "#2e6da4", fontSize: "14px" }} />
          <span style={{ fontWeight: 600, letterSpacing: "0.5px" }}>FTSM GPT</span>
        </div>
        <div className="top-menu-items">
          <Dropdown menu={{ items: chatItems }} trigger={["click"]} placement="bottomLeft">
            <button className="top-menu-item-btn">Chat</button>
          </Dropdown>
          <Dropdown menu={{ items: knowledgeItems }} trigger={["click"]} placement="bottomLeft">
            <button className="top-menu-item-btn">Knowledge</button>
          </Dropdown>
          <Dropdown menu={{ items: consoleItems }} trigger={["click"]} placement="bottomLeft">
            <button className="top-menu-item-btn">Console</button>
          </Dropdown>
          <Dropdown menu={{ items: settingsItems }} trigger={["click"]} placement="bottomLeft">
            <button className="top-menu-item-btn">Settings</button>
          </Dropdown>
          <Dropdown menu={{ items: helpItems }} trigger={["click"]} placement="bottomLeft">
            <button className="top-menu-item-btn">Help</button>
          </Dropdown>
        </div>
      </div>
      <div className="top-menu-right">
        <Text type="secondary" style={{ fontSize: "11px" }}>
          {location.pathname === "/"
            ? "Chat Assistant"
            : location.pathname === "/settings"
            ? "Settings"
            : location.pathname === "/manage"
            ? "Manage Documents"
            : "Dashboard"}
        </Text>
      </div>

      {/* About Modal */}
      <Modal
        title="About FTSM GPT"
        open={aboutOpen}
        onCancel={() => setAboutOpen(false)}
        footer={null}
        width={420}
      >
        <Space direction="vertical" size="middle" style={{ width: "100%", textAlign: "center", padding: "10px 0" }}>
          <CodeOutlined style={{ fontSize: "48px", color: "#2e6da4" }} />
          <div>
            <h2 style={{ margin: "4px 0" }}>FTSM GPT Assistant</h2>
            <Text type="secondary">v0.2.0</Text>
          </div>
          <Text style={{ display: "block" }}>
            A Spring Boot + React + Qdrant RAG assistant designed for UKM FTSM student onboarding guidelines and academic calendars.
          </Text>
          <div style={{ background: "rgba(0,0,0,0.03)", padding: "10px", borderRadius: "6px", fontSize: "12px", textAlign: "left" }}>
            <Text strong>System specifications:</Text>
            <ul style={{ margin: "4px 0 0 16px", padding: 0 }}>
              <li>Model: DashScope Qwen-Turbo</li>
              <li>Vector Store: Qdrant DB (Hybrid Recall)</li>
              <li>Semantic Cache Threshold: 0.92</li>
            </ul>
          </div>
          <Text type="secondary" style={{ fontSize: "12px" }}>
            Project Repo: <AntLink href="https://github.com/zhengbohou0402/FTSM-RAG-Java" target="_blank">zhengbohou0402/FTSM-RAG-Java</AntLink>
          </Text>
        </Space>
      </Modal>
    </div>
  );
}
