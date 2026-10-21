import React, { useEffect, useRef } from 'react';
import { ExecutionProgress } from '../types';

interface LogPanelProps {
  logs: ExecutionProgress[];
  loading: boolean;
}

/**
 * 左侧日志大面板组件
 * 支持逐条输出动效、实时步骤展示与最后一条高亮反馈
 */
export const LogPanel: React.FC<LogPanelProps> = ({ logs, loading }) => {
  const contentAreaRef = useRef<HTMLDivElement>(null);

  // 当日志逐条增加时，自动平滑滚动到底部
  useEffect(() => {
    if (contentAreaRef.current) {
      contentAreaRef.current.scrollTo({
        top: contentAreaRef.current.scrollHeight,
        behavior: 'smooth',
      });
    }
  }, [logs.length]);

  return (
    <div className="log-panel-card">
      <div className="card-header">
        <div>
          <h2 className="card-title">
            <span style={{ fontSize: '18px' }}>📋</span> 业务执行监控日志
          </h2>
          <p className="card-desc">实时反映单据识别、文本分析、总额校验与表格追加的执行过程</p>
        </div>
        {loading && (
          <span style={{ fontSize: '12px', color: '#4a7c59', fontWeight: 500, display: 'flex', alignItems: 'center', gap: '6px' }}>
            <span className="pulsing-dot"></span>
            正在处理中...
          </span>
        )}
      </div>

      <div className="log-content-area" ref={contentAreaRef}>
        {logs.length === 0 ? (
          <div className="log-empty">
            <div style={{ fontSize: '42px', opacity: 0.6 }}>☕</div>
            <p>系统就绪中，请在右侧填写信息并点击【执行】按钮开始</p>
          </div>
        ) : (
          <div className="log-list">
            {logs.map((item, index) => {
              const isLast = index === logs.length - 1;
              const statusClass = item.status;
              const badgeClass =
                item.status === 'RUNNING'
                  ? 'badge-running'
                  : item.status === 'SUCCESS'
                  ? 'badge-success'
                  : 'badge-failed';
              const badgeText =
                item.status === 'RUNNING'
                  ? '进行中'
                  : item.status === 'SUCCESS'
                  ? '成功'
                  : '异常';

              return (
                <div
                  key={`${item.stageCode}-${index}`}
                  className={`log-item log-item-animated ${statusClass} ${isLast && !loading ? 'is-last-result' : ''}`}
                >
                  <span className={`log-badge ${badgeClass}`}>{badgeText}</span>
                  <div className="log-detail">
                    <div className="log-meta">
                      <span className="log-stage">{item.stageName}</span>
                      <span>{item.timestamp}</span>
                    </div>
                    <div className="log-message">{item.message}</div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
