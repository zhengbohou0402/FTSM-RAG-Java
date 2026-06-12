import { useState } from "react";
import type { ComponentPropsWithoutRef } from "react";
import { Card, Typography, Tag, Button } from "antd";
import { DatabaseOutlined, FileTextOutlined, SearchOutlined, CopyOutlined, CheckOutlined } from "@ant-design/icons";
import ReactMarkdown from "react-markdown";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { vscDarkPlus } from "react-syntax-highlighter/dist/esm/styles/prism";
import type { Message } from "../api/client";

const { Text, Paragraph } = Typography;

interface Props {
  message: Message;
}

function guessThinkingType(text: string): string {
  const lower = text.toLowerCase();
  if (lower.includes("search") || lower.includes("knowledge")) return "searching";
  if (lower.includes("cache")) return "cache";
  return "";
}

function processLabel(text: string): string {
  const kind = guessThinkingType(text);
  if (kind === "searching") return "Searching knowledge base";
  if (kind === "cache") return "Answering from cache";
  return text.replace(/\.+$/, "");
}

type CodeBlockProps = ComponentPropsWithoutRef<"code"> & {
  inline?: boolean;
};

function CodeBlock({ inline, className, children, ...props }: CodeBlockProps) {
  const match = /language-(\w+)/.exec(className || "");
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(String(children).replace(/\n$/, ""));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  if (!inline && match) {
    return (
      <div style={{ position: "relative", marginBottom: "1.2em", borderRadius: "8px", overflow: "hidden", boxShadow: "0 4px 12px rgba(0,0,0,0.1)" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", background: "#2d2d2d", padding: "6px 12px", color: "#aaa", fontSize: "12px" }}>
          <span style={{ fontWeight: 600 }}>{match[1]}</span>
          <Button 
            type="text" 
            size="small" 
            icon={copied ? <CheckOutlined style={{ color: "#52c41a" }} /> : <CopyOutlined style={{ color: "#aaa" }} />} 
            onClick={handleCopy} 
            style={{ height: "20px", padding: "0 4px" }}
          >
            {copied ? "Copied" : "Copy"}
          </Button>
        </div>
        <SyntaxHighlighter
          {...props}
          style={vscDarkPlus}
          language={match[1]}
          PreTag="div"
          customStyle={{ margin: 0, borderRadius: "0 0 8px 8px", fontSize: "13px" }}
        >
          {String(children).replace(/\n$/, "")}
        </SyntaxHighlighter>
      </div>
    );
  }
  return (
    <code {...props} className={className} style={{ background: "rgba(150,150,150,0.15)", padding: "2px 6px", borderRadius: "4px", fontSize: "0.9em", color: "#d63384" }}>
      {children}
    </code>
  );
}

export default function ChatMessage({ message }: Props) {
  const isUser = message.role === "user";
  const hasThinking = message.thinking && message.thinking.length > 0;
  const hasContent = message.content && message.content.length > 0;
  const showThinkingPlaceholder = hasThinking && !hasContent;

  return (
    <div className={`message-row ${isUser ? "message-user" : "message-assistant"}`}>
      <div className="message-bubble">
        {(hasThinking || showThinkingPlaceholder) && (
          <div className="process-capsules" style={{ marginBottom: hasContent ? "12px" : "0" }}>
            <span className="process-pill">
              {hasThinking
                ? message.thinking!.map((t, i) => (
                    <span className="process-pill-item" key={i}>
                      {guessThinkingType(t) === "searching" && <SearchOutlined />}
                      {guessThinkingType(t) === "cache" && <DatabaseOutlined />}
                      <span>{processLabel(t)}</span>
                      {i < message.thinking!.length - 1 && <span className="process-divider">/</span>}
                    </span>
                  ))
                : "Analyzing..."}
            </span>
          </div>
        )}

        {isUser ? (
          <Text>{message.content}</Text>
        ) : hasContent ? (
          <div className="markdown-body">
            <ReactMarkdown components={{ code: CodeBlock }}>
              {message.content}
            </ReactMarkdown>
          </div>
        ) : null}

        {message.sources && message.sources.length > 0 && (
          <div className="sources-horizontal-container" style={{ marginTop: "16px" }}>
            <div style={{ marginBottom: "8px", fontSize: "12px", color: "var(--text-muted)", display: "flex", alignItems: "center", gap: "6px" }}>
              <FileTextOutlined /> <span>{message.sources.length} sources found</span>
            </div>
            <div style={{ display: "flex", gap: "12px", overflowX: "auto", paddingBottom: "8px" }} className="hide-scrollbar">
              {message.sources.map((s, i) => (
                <Card 
                  key={i} 
                  size="small" 
                  className="source-glass-card"
                  style={{ 
                    minWidth: "220px", 
                    maxWidth: "260px", 
                    flexShrink: 0, 
                    borderRadius: "12px", 
                    background: "var(--glass-bg)", 
                    border: "1px solid var(--glass-border)",
                    boxShadow: "0 4px 12px rgba(0,0,0,0.02)"
                  }}
                >
                  <div style={{ display: "flex", gap: "6px", marginBottom: "8px", flexWrap: "wrap" }}>
                    <Tag color="blue" bordered={false} style={{ maxWidth: "100%", overflow: "hidden", textOverflow: "ellipsis" }}>{s.file}</Tag>
                    {s.source_type && <Tag color="green" bordered={false}>{s.source_type}</Tag>}
                  </div>
                  <Paragraph ellipsis={{ rows: 3 }} style={{ fontSize: "12px", color: "var(--text-muted)", margin: 0, lineHeight: 1.5 }}>
                    {s.excerpt}
                  </Paragraph>
                </Card>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
