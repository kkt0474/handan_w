/**
 * 进度状态类型
 */
export type ProgressStatus = 'RUNNING' | 'SUCCESS' | 'FAILED';

/**
 * 后端推送的单条执行进度日志项
 */
export interface ExecutionProgress {
  stageCode: string;
  stageName: string;
  message: string;
  status: ProgressStatus;
  timestamp: string;
}

/**
 * 订单接口响应模型
 */
export interface OrderExecuteResponse {
  success: boolean;
  message: string;
  generatedFile?: string;
  logs: ExecutionProgress[];
}

/**
 * 业务功能模块定义（用于右侧模块列表扩展）
 */
export interface BusinessModule {
  id: string;
  title: string;
  description: string;
  status: 'active' | 'pending';
}

/**
 * 采购单可用 Excel 文件列表响应
 */
export interface PurchaseFilesResponse {
  files: string[];
  recommendedSource?: string | null;
  recommendedTarget?: string | null;
  scannedDir?: string | null;
  errorMessage?: string | null;
}
