import React from 'react';

export type OrderFileType = 'AUTO' | 'PDF' | 'PNG';

interface OrderModuleCardProps {
  customer: string;
  orderNo: string;
  checkTotalAmount: string;
  fileType: OrderFileType;
  loading: boolean;
  onCustomerChange: (val: string) => void;
  onOrderNoChange: (val: string) => void;
  onCheckTotalAmountChange: (val: string) => void;
  onFileTypeChange: (val: OrderFileType) => void;
  onExecute: () => void;
}

/**
 * 订单功能卡片（模块 1）
 * 包含必填字段校验、防呆控制、单据类型选择、选填金额校验字段与执行触发
 */
export const OrderModuleCard: React.FC<OrderModuleCardProps> = ({
  customer,
  orderNo,
  checkTotalAmount,
  fileType,
  loading,
  onCustomerChange,
  onOrderNoChange,
  onCheckTotalAmountChange,
  onFileTypeChange,
  onExecute,
}) => {
  // 校验逻辑：客户与订单号两个字段均为必填，为空时禁止点击执行按钮
  const isFormValid = customer.trim().length > 0 && orderNo.trim().length > 0;

  return (
    <div className="module-card">
      <div className="module-header">
        <h3 className="module-title">📑 客户订单</h3>
        <span className="module-status-badge status-active">已启用</span>
      </div>

      <div className="form-group">
        <label className="form-label">
          单据类型 <span style={{ fontSize: '11px', color: 'var(--text-sub)', fontWeight: 'normal' }}>(数据源文件格式)</span>
        </label>
        <div className="file-type-toggle">
          <button
            type="button"
            className={`file-type-btn ${fileType === 'AUTO' ? 'active' : ''}`}
            onClick={() => onFileTypeChange('AUTO')}
            disabled={loading}
          >
            ⚡ 自动检测
          </button>
          <button
            type="button"
            className={`file-type-btn ${fileType === 'PDF' ? 'active' : ''}`}
            onClick={() => onFileTypeChange('PDF')}
            disabled={loading}
          >
            📄 PDF 单据
          </button>
          <button
            type="button"
            className={`file-type-btn ${fileType === 'PNG' ? 'active' : ''}`}
            onClick={() => onFileTypeChange('PNG')}
            disabled={loading}
          >
            🖼️ PNG 图片
          </button>
        </div>
      </div>

      <div className="form-group">
        <label className="form-label">
          客户 <span className="required-star">*</span>
        </label>
        <input
          type="text"
          className="form-input"
          placeholder="请输入客户名称（必填）"
          value={customer}
          onChange={(e) => onCustomerChange(e.target.value)}
          disabled={loading}
        />
      </div>

      <div className="form-group">
        <label className="form-label">
          订单号 <span className="required-star">*</span>
        </label>
        <input
          type="text"
          className="form-input"
          placeholder="请输入订单号（必填）"
          value={orderNo}
          onChange={(e) => onOrderNoChange(e.target.value)}
          disabled={loading}
        />
      </div>

      <div className="form-group">
        <label className="form-label">
          总金额(校验) <span style={{ fontSize: '11px', color: 'var(--text-sub)', fontWeight: 'normal' }}>(选填，比对单价*数量)</span>
        </label>
        <input
          type="text"
          className="form-input"
          placeholder="例如：13200.00（用于核对总额）"
          value={checkTotalAmount}
          onChange={(e) => onCheckTotalAmountChange(e.target.value)}
          disabled={loading}
        />
      </div>

      <button
        type="button"
        className="btn-execute"
        disabled={!isFormValid || loading}
        onClick={onExecute}
      >
        {loading ? '正在解析追加中...' : '▶ 执行'}
      </button>
    </div>
  );
};
