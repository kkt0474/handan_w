import React from 'react';
import { BusinessModule } from '../types';

interface PlaceholderCardProps {
  module: BusinessModule;
}

/**
 * 占位功能卡片（如：采购单、发票等待扩展模块）
 */
export const PlaceholderCard: React.FC<PlaceholderCardProps> = ({ module }) => {
  return (
    <div className="module-card is-pending">
      <div className="module-header">
        <h3 className="module-title" style={{ opacity: 0.75 }}>
          📦 {module.title}
        </h3>
        <span className="module-status-badge status-pending">开发中</span>
      </div>

      <div className="pending-placeholder-content">
        <span>{module.description}</span>
      </div>

      <button type="button" className="btn-execute" disabled>
        ▶ 执行 (暂未启用)
      </button>
    </div>
  );
};
