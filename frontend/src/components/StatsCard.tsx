import { Typography } from "antd";

const { Text } = Typography;

interface Props {
  label: string;
  value: string | number;
  sub?: string;
  accent?: "blue" | "green" | "amber" | "purple" | "red";
}

const accentColors: Record<string, string> = {
  blue: "#2e6da4",
  green: "#22c55e",
  amber: "#f59e0b",
  purple: "#8b5cf6",
  red: "#ef4444",
};

export default function StatsCard({ label, value, sub, accent }: Props) {
  return (
    <div
      className="stats-card"
      style={accent ? { borderLeftColor: accentColors[accent] } : undefined}
    >
      <div className="stats-card-value">{value}</div>
      <div className="stats-card-label">{label}</div>
      {sub && <Text type="secondary" style={{ fontSize: 11 }}>{sub}</Text>}
    </div>
  );
}
