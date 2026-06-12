import { useEffect, useState, useCallback } from "react";
import { Button, Typography, Space } from "antd";
import {
  DatabaseOutlined,
  FileSearchOutlined,
  MessageOutlined,
  ReloadOutlined,
  RobotOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { api } from "../api/client";
import type { KnowledgeStats, CacheStats, TrainingStatus, SchedulerStatus } from "../api/client";
import StatsCard from "../components/StatsCard";
import StatusBadge from "../components/StatusBadge";

const { Text } = Typography;

function fmtPast(iso: string | null): string {
  if (!iso) return "-";
  const diff = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (diff < 60) return "just now";
  if (diff < 3600) return Math.floor(diff / 60) + "m ago";
  if (diff < 86400) return Math.floor(diff / 3600) + "h ago";
  return Math.floor(diff / 86400) + "d ago";
}

function fmtFuture(iso: string | null): string {
  if (!iso) return "-";
  const diff = (new Date(iso).getTime() - Date.now()) / 1000;
  if (diff <= 0) return "due now";
  if (diff < 3600) return "in " + Math.ceil(diff / 60) + "m";
  if (diff < 86400) return "in " + Math.ceil(diff / 3600) + "h";
  return "in " + Math.ceil(diff / 86400) + "d";
}

function sourceTypeLabel(sourceType: string): string {
  const labels: Record<string, string> = {
    official: "Official",
    community_guide: "Student Guide",
    scraped_website: "Scraped Website",
    generated_summary: "Generated Summary",
  };
  return labels[sourceType] || sourceType || "Unknown";
}

const COLORS = ["#0088FE", "#00C49F", "#FFBB28", "#FF8042", "#8884d8"];

type DonutDatum = {
  name: string;
  value: number;
};

function DonutChart({ data, colors = COLORS }: { data: DonutDatum[]; colors?: string[] }) {
  const total = data.reduce((sum, item) => sum + Math.max(0, item.value), 0);
  const radius = 34;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;

  return (
    <div className="donut-chart" role="img" aria-label={`Total ${total}`}>
      <svg className="donut-chart-graphic" viewBox="0 0 100 100" aria-hidden="true">
        <circle className="donut-chart-track" cx="50" cy="50" r={radius} />
        {total > 0 && data.map((item, index) => {
          const length = (Math.max(0, item.value) / total) * circumference;
          const dashOffset = -offset;
          offset += length;
          return (
            <circle
              className="donut-chart-segment"
              cx="50"
              cy="50"
              r={radius}
              key={item.name}
              stroke={colors[index % colors.length]}
              strokeDasharray={`${length} ${circumference - length}`}
              strokeDashoffset={dashOffset}
            >
              <title>{`${item.name}: ${item.value}`}</title>
            </circle>
          );
        })}
        <text className="donut-chart-total" x="50" y="50">{total}</text>
      </svg>
      <div className="donut-chart-legend">
        {data.map((item, index) => (
          <div className="donut-chart-legend-item" key={item.name}>
            <span
              className="donut-chart-swatch"
              style={{ backgroundColor: colors[index % colors.length] }}
            />
            <span>{item.name}</span>
            <strong>{item.value}</strong>
          </div>
        ))}
      </div>
    </div>
  );
}

export default function Dashboard() {
  const [kb, setKb] = useState<KnowledgeStats | null>(null);
  const [cache, setCache] = useState<CacheStats | null>(null);
  const [training, setTraining] = useState<TrainingStatus | null>(null);
  const [scheduler, setScheduler] = useState<SchedulerStatus | null>(null);
  const [convCount, setConvCount] = useState<number>(0);
  const [updatedAt, setUpdatedAt] = useState("");

  const loadAll = useCallback(async () => {
    try {
      const [kbData, cacheData, trainData, schedData, convs] = await Promise.all([
        api.knowledge.stats(),
        api.cache.stats(),
        api.training.status(),
        api.scheduler.status(),
        api.conversations.list(),
      ]);
      setKb(kbData);
      setCache(cacheData);
      setTraining(trainData);
      setScheduler(schedData);
      setConvCount(convs.items.length);
    } catch {
      // keep previous values visible
    }
    setUpdatedAt(new Date().toLocaleTimeString());
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(() => {
      void loadAll();
    }, 0);
    const interval = window.setInterval(() => {
      void loadAll();
    }, 30000);
    return () => {
      window.clearTimeout(initial);
      window.clearInterval(interval);
    };
  }, [loadAll]);

  const trainStatus: "running" | "idle" | "error" | "success" =
    training?.running ? "running" :
    training?.pending ? "running" :
    training?.last_error ? "error" :
    training?.last_result === "success" ? "success" : "idle";

  const pipeline = [
    { icon: <MessageOutlined />, label: "User Query", sub: "question + history" },
    { icon: <SearchOutlined />, label: "Vector Search", sub: "semantic retrieval" },
    { icon: <FileSearchOutlined />, label: "BM25 Search", sub: "keyword retrieval" },
    { icon: <DatabaseOutlined />, label: "RRF Fusion", sub: "merge candidates" },
    { icon: <DatabaseOutlined />, label: "Source Trust", sub: "authority weighting" },
    { icon: <RobotOutlined />, label: "LLM Answer", sub: "Qwen response" },
  ];

  // Prepare chart data
  const sourceChartData = Object.entries(kb?.source_type_counts || {}).map(([type, count]) => ({
    name: sourceTypeLabel(type),
    value: count,
  }));

  const cacheChartData = [
    { name: "Hits", value: cache?.hit_count || 0 },
    { name: "Misses", value: cache?.miss_count || 0 },
  ];

  return (
    <div className="admin-shell dash-shell">
      <div className="admin-header dash-header">
        <div>
          <Text type="secondary" className="page-kicker">FTSM GPT</Text>
          <h1>System Dashboard</h1>
          <p>Monitor retrieval, knowledge-base indexing, cache usage, and crawler scheduling.</p>
        </div>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={loadAll}>Refresh</Button>
        </Space>
      </div>

      <section className="admin-section">
        <Text strong className="section-kicker">Retrieval Pipeline</Text>
        <div className="pipeline">
          {pipeline.map((step, idx) => (
            <div className="pipeline-item" key={step.label}>
              <div className="pipeline-step">
                <div className="pipeline-step-icon">{step.icon}</div>
                <div className="pipeline-step-label">{step.label}</div>
                <div className="pipeline-step-sub">{step.sub}</div>
              </div>
              {idx < pipeline.length - 1 && <div className="pipeline-arrow">-&gt;</div>}
            </div>
          ))}
        </div>
      </section>

      <section className="admin-section">
        <Text strong className="section-kicker">Knowledge Base</Text>
        <div className="stats-grid">
          <StatsCard label="Documents" value={kb?.document_count ?? "-"} sub="uploaded files" accent="blue" />
          <StatsCard label="Vector Chunks" value={kb?.total_chunks ?? "-"} sub="indexed in Qdrant" accent="purple" />
          <StatsCard label="Index Version" value={kb?.index_version ?? "-"} sub="manifest version" accent="green" />
          <StatsCard
            label="Last Indexed"
            value={fmtPast(kb?.last_indexed ?? null)}
            sub={kb?.last_indexed ? new Date(kb.last_indexed).toLocaleString() : undefined}
          />
        </div>
      </section>

      <section className="admin-section">
        <Text strong className="section-kicker">Source Trust And Index Version</Text>
        <div className="dash-two-column">
          <div className="admin-panel">
            <div className="panel-title-row">
              <Text strong>Source Type Distribution</Text>
              <Text type="secondary">{kb?.manifest_records ?? "-"} records</Text>
            </div>
            {sourceChartData.length > 0 ? (
              <DonutChart data={sourceChartData} />
            ) : (
              <Text type="secondary">No source types recorded yet.</Text>
            )}
          </div>

          <div className="admin-panel">
            <div className="panel-title-row">
              <Text strong>Index Version State</Text>
              <StatusBadge status={kb?.index_last_error ? "error" : "success"} />
            </div>
            <div className="info-grid">
              <div>
                <Text type="secondary">Updated At</Text>
                <strong>{kb?.index_updated_at ? new Date(kb.index_updated_at).toLocaleString() : "-"}</strong>
              </div>
              <div>
                <Text type="secondary">Last Error</Text>
                <strong>{kb?.index_last_error || "-"}</strong>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="admin-section">
        <Text strong className="section-kicker">Cache And Workers</Text>
        <div className="dash-two-column">
          <div className="admin-panel">
            <div className="panel-title-row">
              <Text strong>Semantic Cache Hit Rate</Text>
              <Text type="secondary">{cache?.size ?? "-"} entries</Text>
            </div>
            <DonutChart data={cacheChartData} colors={["#52c41a", "#faad14"]} />
            <div className="stats-grid compact-stats-grid">
              <StatsCard label="Hits" value={cache?.hit_count ?? "-"} accent="green" />
              <StatsCard label="Misses" value={cache?.miss_count ?? "-"} accent="amber" />
              <StatsCard label="Threshold" value={cache?.threshold ?? "-"} />
            </div>
          </div>

          <div className="admin-panel worker-panel">
            <div className="panel-title-row">
              <Text strong>Indexing Worker</Text>
              <StatusBadge status={trainStatus} />
            </div>
            <div className="info-grid">
              <div><Text type="secondary">Last Result</Text><strong>{training?.last_result || "-"}</strong></div>
              <div><Text type="secondary">Last Error</Text><strong>{training?.last_error || "-"}</strong></div>
            </div>
          </div>
        </div>
      </section>

      <section className="admin-section">
        <Text strong className="section-kicker">Conversations And Crawler</Text>
        <div className="stats-grid">
          <StatsCard label="Conversations" value={convCount} sub="stored on disk" accent="blue" />
          <StatsCard
            label="Crawler Scheduler"
            value={scheduler?.enabled ? "Enabled" : "Disabled"}
            sub={scheduler?.enabled ? `every ${scheduler.interval_hours}h` : "frozen / disabled"}
          />
          <StatsCard
            label="Last Crawl"
            value={fmtPast(scheduler?.last_run ?? null)}
            sub={scheduler?.next_run ? `next: ${fmtFuture(scheduler.next_run)}` : "-"}
          />
        </div>
      </section>

      <div className="admin-updated">
        <Text type="secondary">Last updated: {updatedAt || "-"}</Text>
      </div>
    </div>
  );
}
