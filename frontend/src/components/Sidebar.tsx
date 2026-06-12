import { List, Button, Typography } from "antd";
import { PlusOutlined, DeleteOutlined, MessageOutlined } from "@ant-design/icons";
import type { Conversation } from "../api/client";

const { Text } = Typography;

interface Props {
  conversations: Conversation[];
  activeId: string | null;
  loading: boolean;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (id: string) => void;
}

export default function Sidebar({ conversations, activeId, loading, onSelect, onNew, onDelete }: Props) {
  return (
    <div className="sidebar">
      <div className="sidebar-head">
        <Button type="primary" block icon={<PlusOutlined />} onClick={onNew}>
          New chat
        </Button>
      </div>
      <div className="sidebar-list">
        <List
          loading={loading}
          dataSource={conversations}
          renderItem={(item) => (
            <div
              className={`sidebar-item ${item.id === activeId ? "active" : ""}`}
              onClick={() => onSelect(item.id)}
            >
              <MessageOutlined style={{ marginRight: 8, flexShrink: 0 }} />
              <Text ellipsis style={{ flex: 1 }}>
                {item.title || "New chat"}
              </Text>
              <Button
                type="text"
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  onDelete(item.id);
                }}
              />
            </div>
          )}
        />
      </div>
    </div>
  );
}
