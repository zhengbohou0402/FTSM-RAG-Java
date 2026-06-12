import { useState } from "react";
import { Button, Input } from "antd";
import { SendOutlined } from "@ant-design/icons";

const { TextArea } = Input;

interface Props {
  onSend: (text: string) => void;
  disabled: boolean;
}

const SUGGESTIONS = [
  { title: "Academic Calendar", prompt: "Give me the key academic calendar dates for this academic year." },
  { title: "Course Timetable", prompt: "Give me the information I need to understand my course timetable." },
  { title: "Admission Info", prompt: "Summarize the admission requirements for FTSM postgraduate programs." },
  { title: "Visa Renewal", prompt: "Explain the student visa renewal steps and required documents." },
  { title: "Campus Bus", prompt: "Give me the UKM campus bus route information relevant to students." },
  { title: "Staff Directory", prompt: "Give me information about FTSM academic staff and their expertise." },
  { title: "Registration", prompt: "Explain what I should prepare for course registration renewal." },
  { title: "Industrial Training", prompt: "Summarize the industrial training information and important contacts." },
  { title: "Facilities", prompt: "List the facilities and services available at FTSM." },
  { title: "Public Holidays", prompt: "Give me the Malaysian public holiday dates for this academic year." },
  { title: "Student Systems", prompt: "Explain the UKM student systems used for academic matters." },
  { title: "Exam Schedule", prompt: "Give me the final exam schedule information and what I should check." },
];

export default function Composer({ onSend, disabled }: Props) {
  const [text, setText] = useState("");

  const handleSend = () => {
    const trimmed = text.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setText("");
  };

  return (
    <div className="composer-wrap">
      <div className="suggestions-row">
        {SUGGESTIONS.map((s) => (
          <Button
            key={s.title}
            size="small"
            className="suggestion-chip"
            disabled={disabled}
            onClick={() => onSend(s.prompt)}
          >
            {s.title}
          </Button>
        ))}
      </div>
      <div className="composer">
        <TextArea
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder="Message FTSM-RAG Assistant..."
          autoSize={{ minRows: 1, maxRows: 6 }}
          maxLength={1500}
          disabled={disabled}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          onClick={handleSend}
          disabled={disabled || !text.trim()}
        />
      </div>
      <p className="disclaimer">
        This assistant can make mistakes. Verify critical deadlines through official UKM channels.
      </p>
    </div>
  );
}
