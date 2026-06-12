interface Props {
  status: "running" | "idle" | "error" | "success";
  label?: string;
}

const statusConfig = {
  running: { color: "#f59e0b", bg: "#fef3c7", text: "#92400e", label: "Running" },
  idle: { color: "#9ca3af", bg: "#f3f4f6", text: "#555", label: "Idle" },
  error: { color: "#ef4444", bg: "#fee2e2", text: "#991b1b", label: "Error" },
  success: { color: "#22c55e", bg: "#dcfce7", text: "#166534", label: "Success" },
};

export default function StatusBadge({ status, label }: Props) {
  const config = statusConfig[status];
  return (
    <span
      className="status-badge"
      style={{ background: config.bg, color: config.text }}
    >
      <span
        className="status-dot"
        style={{
          background: config.color,
          animation: status === "running" ? "pulse 0.9s infinite" : undefined,
        }}
      />
      {label || config.label}
    </span>
  );
}
