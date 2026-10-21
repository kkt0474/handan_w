import React, { useState, useRef, useEffect } from 'react';

interface DatePickerProps {
  value: string; // 例如 "2026/10/14" 或 "2026-10-14"
  onChange: (dateStr: string) => void;
  disabled?: boolean;
  placeholder?: string;
}

/**
 * 日式小清新风格日期选择组件
 * 支持快捷年月切换、今天定位、平滑悬浮弹窗与外部点击自动收起
 */
export const DatePicker: React.FC<DatePickerProps> = ({
  value,
  onChange,
  disabled = false,
  placeholder = '请选择日期',
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // 解析日期为 { year, month, day }
  const parseDate = (val: string): { year: number; month: number; day: number } => {
    if (!val || !val.trim()) {
      const now = new Date();
      return { year: now.getFullYear(), month: now.getMonth(), day: now.getDate() };
    }
    const parts = val.trim().split(/[/-]/).map((n) => parseInt(n, 10));
    if (parts.length === 3 && !isNaN(parts[0]) && !isNaN(parts[1]) && !isNaN(parts[2])) {
      return { year: parts[0], month: parts[1] - 1, day: parts[2] };
    }
    const now = new Date();
    return { year: now.getFullYear(), month: now.getMonth(), day: now.getDate() };
  };

  const parsed = parseDate(value);
  const [viewYear, setViewYear] = useState(parsed.year);
  const [viewMonth, setViewMonth] = useState(parsed.month); // 0-11

  // 外部 value 变更时同步视窗年月
  useEffect(() => {
    const p = parseDate(value);
    setViewYear(p.year);
    setViewMonth(p.month);
  }, [value]);

  // 点击外部自动收起下拉日历
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  const prevMonth = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (viewMonth === 0) {
      setViewYear((y) => y - 1);
      setViewMonth(11);
    } else {
      setViewMonth((m) => m - 1);
    }
  };

  const nextMonth = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (viewMonth === 11) {
      setViewYear((y) => y + 1);
      setViewMonth(0);
    } else {
      setViewMonth((m) => m + 1);
    }
  };

  const selectDay = (day: number) => {
    const mStr = String(viewMonth + 1).padStart(2, '0');
    const dStr = String(day).padStart(2, '0');
    onChange(`${viewYear}/${mStr}/${dStr}`);
    setIsOpen(false);
  };

  const selectToday = (e: React.MouseEvent) => {
    e.stopPropagation();
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    onChange(`${y}/${m}/${d}`);
    setViewYear(y);
    setViewMonth(now.getMonth());
    setIsOpen(false);
  };

  const getDaysInMonth = (y: number, m: number) => new Date(y, m + 1, 0).getDate();
  const getFirstDayOfWeek = (y: number, m: number) => new Date(y, m, 1).getDay(); // 0 是周日

  const daysInCurrentMonth = getDaysInMonth(viewYear, viewMonth);
  const startDayOfWeek = getFirstDayOfWeek(viewYear, viewMonth);

  const prevMonthDays = getDaysInMonth(
    viewMonth === 0 ? viewYear - 1 : viewYear,
    viewMonth === 0 ? 11 : viewMonth - 1
  );

  const now = new Date();
  const isCurrentMonthToday =
    now.getFullYear() === viewYear && now.getMonth() === viewMonth;
  const todayDate = now.getDate();

  const isSelected = (day: number) => {
    return (
      parsed.year === viewYear &&
      parsed.month === viewMonth &&
      parsed.day === day &&
      Boolean(value && value.trim())
    );
  };

  const weekdays = ['日', '一', '二', '三', '四', '五', '六'];

  return (
    <div className="date-picker-wrapper" ref={containerRef}>
      <div
        className={`date-picker-input-container ${disabled ? 'disabled' : ''}`}
        onClick={() => !disabled && setIsOpen(!isOpen)}
      >
        <input
          type="text"
          className="form-input date-input"
          placeholder={placeholder}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
        />
        <span className="calendar-icon" title="选择日期">📅</span>
      </div>

      {isOpen && (
        <div className="date-picker-dropdown">
          <div className="date-picker-header">
            <button type="button" className="nav-btn" onClick={prevMonth} title="上一月">
              ‹
            </button>
            <span className="current-month-label">
              {viewYear}年 {viewMonth + 1}月
            </span>
            <button type="button" className="nav-btn" onClick={nextMonth} title="下一月">
              ›
            </button>
          </div>

          <div className="date-picker-weekdays">
            {weekdays.map((w, idx) => (
              <span key={idx} className="weekday-label">
                {w}
              </span>
            ))}
          </div>

          <div className="date-picker-grid">
            {/* 上个月在当前视图的填充格 */}
            {Array.from({ length: startDayOfWeek }).map((_, i) => {
              const dayNum = prevMonthDays - startDayOfWeek + i + 1;
              return (
                <span key={`prev-${i}`} className="day-cell other-month">
                  {dayNum}
                </span>
              );
            })}

            {/* 当月日期格 */}
            {Array.from({ length: daysInCurrentMonth }).map((_, i) => {
              const dayNum = i + 1;
              const selected = isSelected(dayNum);
              const isToday = isCurrentMonthToday && dayNum === todayDate;

              return (
                <button
                  type="button"
                  key={`cur-${dayNum}`}
                  className={`day-cell current-month ${selected ? 'selected' : ''} ${
                    isToday ? 'today' : ''
                  }`}
                  onClick={(e) => {
                    e.stopPropagation();
                    selectDay(dayNum);
                  }}
                >
                  {dayNum}
                </button>
              );
            })}
          </div>

          <div className="date-picker-footer">
            <button
              type="button"
              className="quick-today-btn"
              onClick={selectToday}
            >
              设为今天
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
