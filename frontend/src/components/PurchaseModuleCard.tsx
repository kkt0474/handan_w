import React from 'react';
import { DatePicker } from './DatePicker';

interface PurchaseModuleCardProps {
  date: string;
  supplier: string;
  purchaseOrderNo: string;
  checkTotalQuantity: string;
  checkTotalAmount: string;
  files: string[];
  sourceFile: string;
  targetFile: string;
  scannedDir?: string;
  scanErrorMessage?: string;
  isRefreshingFiles: boolean;
  loading: boolean;
  onDateChange: (val: string) => void;
  onSupplierChange: (val: string) => void;
  onPurchaseOrderNoChange: (val: string) => void;
  onCheckTotalQuantityChange: (val: string) => void;
  onCheckTotalAmountChange: (val: string) => void;
  onSourceFileChange: (val: string) => void;
  onTargetFileChange: (val: string) => void;
  onRefreshFiles: () => void;
  onExecute: () => void;
}

/**
 * 采购单功能卡片（模块 2）
 * 包含工作区 Excel 文件精准选择、必填字段校验、防呆控制、选填校验字段与执行触发
 */
export const PurchaseModuleCard: React.FC<PurchaseModuleCardProps> = ({
  date,
  supplier,
  purchaseOrderNo,
  checkTotalQuantity,
  checkTotalAmount,
  files,
  sourceFile,
  targetFile,
  scannedDir,
  scanErrorMessage,
  isRefreshingFiles,
  loading,
  onDateChange,
  onSupplierChange,
  onPurchaseOrderNoChange,
  onCheckTotalQuantityChange,
  onCheckTotalAmountChange,
  onSourceFileChange,
  onTargetFileChange,
  onRefreshFiles,
  onExecute,
}) => {
  const isSameFile = Boolean(sourceFile && targetFile && sourceFile === targetFile);

  // 校验逻辑：日期、供应商、采购单号以及源单据和目标母表均为必填，且不能为同一个文件
  const isFormValid =
    date.trim().length > 0 &&
    supplier.trim().length > 0 &&
    purchaseOrderNo.trim().length > 0 &&
    sourceFile.trim().length > 0 &&
    targetFile.trim().length > 0 &&
    !isSameFile;

  return (
    <div className="module-card">
      <div className="module-header">
        <h3 className="module-title">📦 采购单</h3>
        <span className="module-status-badge status-active">已启用</span>
      </div>

      {/* Excel 关联选择区 */}
      <div
        style={{
          background: '#f8faf9',
          border: '1px solid #e1ebe5',
          borderRadius: '8px',
          padding: '12px 14px',
          marginBottom: '16px',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
          <div>
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-main, #2d3748)' }}>
              📁 关联 Excel 文件配置
            </span>
            <span style={{ fontSize: '11px', color: files.length < 2 ? '#e53e3e' : '#4a5568', marginLeft: '8px' }}>
              ({files.length} 个文件可用{files.length === 1 ? '，母表待放入' : ''})
            </span>
          </div>
          <button
            type="button"
            onClick={onRefreshFiles}
            disabled={loading || isRefreshingFiles}
            style={{
              background: 'none',
              border: 'none',
              color: '#3182ce',
              cursor: loading || isRefreshingFiles ? 'not-allowed' : 'pointer',
              fontSize: '12px',
              padding: '2px 6px',
              borderRadius: '4px',
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            {isRefreshingFiles ? '扫描中...' : '↻ 刷新文件列表'}
          </button>
        </div>

        {scannedDir && (
          <div style={{ fontSize: '11px', color: '#718096', marginBottom: scanErrorMessage ? '6px' : '10px', wordBreak: 'break-all' }}>
            工作目录: {scannedDir}
          </div>
        )}

        {scanErrorMessage && (
          <div
            style={{
              background: '#fff5f5',
              border: '1px solid #fed7d7',
              borderRadius: '6px',
              padding: '8px 10px',
              color: '#c53030',
              fontSize: '11px',
              lineHeight: 1.4,
              marginBottom: '10px',
            }}
          >
            ⚠️ {scanErrorMessage}
          </div>
        )}

        {/* 采购源单据下拉 */}
        <div className="form-group" style={{ marginBottom: '10px' }}>
          <label className="form-label" style={{ fontSize: '12px', marginBottom: '4px' }}>
            采购源单据 (Source) <span className="required-star">*</span>
            <span style={{ fontSize: '11px', color: '#718096', marginLeft: '6px' }}>(包含 No/P/N/数量等明细)</span>
          </label>
          <select
            className="form-input"
            value={sourceFile}
            onChange={(e) => onSourceFileChange(e.target.value)}
            disabled={loading}
            style={{ fontSize: '13px', padding: '6px 10px', height: '36px' }}
          >
            <option value="">-- 请选择采购源单据 Excel --</option>
            {files.map((f) => (
              <option key={f} value={f}>
                📄 {f}
              </option>
            ))}
          </select>
        </div>

        {/* 目标母表下拉 */}
        <div className="form-group" style={{ marginBottom: '0' }}>
          <label className="form-label" style={{ fontSize: '12px', marginBottom: '4px' }}>
            目标母表 (Target) <span className="required-star">*</span>
            <span style={{ fontSize: '11px', color: '#718096', marginLeft: '6px' }}>(包含【采购单】及【采购单汇总】)</span>
          </label>
          <select
            className="form-input"
            value={targetFile}
            onChange={(e) => onTargetFileChange(e.target.value)}
            disabled={loading}
            style={{ fontSize: '13px', padding: '6px 10px', height: '36px' }}
          >
            <option value="">-- 请选择目标写入母表 Excel --</option>
            {files.map((f) => (
              <option key={f} value={f}>
                📊 {f}
              </option>
            ))}
          </select>
        </div>

        {isSameFile && (
          <div style={{ color: '#e53e3e', fontSize: '12px', marginTop: '8px', display: 'flex', alignItems: 'center', gap: '4px' }}>
            <span>⚠️</span> 采购源单据与目标母表不能是同一个文件，请分别选择对应文件。
          </div>
        )}
      </div>

      <div className="form-group">
        <label className="form-label">
          日期 <span className="required-star">*</span>
        </label>
        <DatePicker
          value={date}
          onChange={onDateChange}
          disabled={loading}
          placeholder="请选择或输入日期（例如 2026/10/14）"
        />
      </div>

      <div className="form-group">
        <label className="form-label">
          供应商 <span className="required-star">*</span>
        </label>
        <input
          type="text"
          className="form-input"
          placeholder="请输入供应商名称（必填）"
          value={supplier}
          onChange={(e) => onSupplierChange(e.target.value)}
          disabled={loading}
        />
      </div>

      <div className="form-group">
        <label className="form-label">
          采购单号 <span className="required-star">*</span>
        </label>
        <input
          type="text"
          className="form-input"
          placeholder="请输入采购单号（必填）"
          value={purchaseOrderNo}
          onChange={(e) => onPurchaseOrderNoChange(e.target.value)}
          disabled={loading}
        />
      </div>

      <div className="form-group">
        <label className="form-label">
          总数量(校验){' '}
          <span style={{ fontSize: '11px', color: 'var(--text-sub)', fontWeight: 'normal' }}>
            (选填，比对数量求和)
          </span>
        </label>
        <input
          type="text"
          className="form-input"
          placeholder="例如：43,000（用于核对总数量）"
          value={checkTotalQuantity}
          onChange={(e) => onCheckTotalQuantityChange(e.target.value)}
          disabled={loading}
        />
      </div>

      <div className="form-group">
        <label className="form-label">
          总金额(校验){' '}
          <span style={{ fontSize: '11px', color: 'var(--text-sub)', fontWeight: 'normal' }}>
            (选填，比对单价*数量求和)
          </span>
        </label>
        <input
          type="text"
          className="form-input"
          placeholder="例如：25,100.00（用于核对总金额）"
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
        {loading ? '正在读取追加中...' : '▶ 执行采购单处理'}
      </button>
    </div>
  );
};
