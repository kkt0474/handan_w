import React, { useState, useRef, useEffect } from 'react';
import { ExecutionProgress, BusinessModule, OrderExecuteResponse, PurchaseFilesResponse } from './types';
import { LogPanel } from './components/LogPanel';
import { OrderModuleCard, OrderFileType } from './components/OrderModuleCard';
import { PurchaseModuleCard } from './components/PurchaseModuleCard';
import { PlaceholderCard } from './components/PlaceholderCard';
import './styles/app.css';

/**
 * 业务模块配置列表
 */
const INITIAL_MODULES: BusinessModule[] = [
  {
    id: 'order',
    title: '客户订单',
    description: '单据数据解析、总额校验与明细追加',
    status: 'active',
  },
  {
    id: 'purchase',
    title: '采购单',
    description: 'source源表提取、双重校验与数据追加',
    status: 'active',
  },
  {
    id: 'invoice',
    title: '发票核验',
    description: '增值税单据明细比对与沉淀（待开放）',
    status: 'pending',
  },
];

const getTodayString = (): string => {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}/${m}/${d}`;
};

export const App: React.FC = () => {
  // 客户订单表单状态
  const [customer, setCustomer] = useState<string>('');
  const [orderNo, setOrderNo] = useState<string>('');
  const [checkTotalAmount, setCheckTotalAmount] = useState<string>('');
  const [orderFileType, setOrderFileType] = useState<OrderFileType>('AUTO');

  // 采购单表单状态
  const [purchaseDate, setPurchaseDate] = useState<string>(getTodayString());
  const [supplier, setSupplier] = useState<string>('');
  const [purchaseOrderNo, setPurchaseOrderNo] = useState<string>('');
  const [checkTotalQuantity, setCheckTotalQuantity] = useState<string>('');
  const [purchaseCheckTotalAmount, setPurchaseCheckTotalAmount] = useState<string>('');

  // 采购单 Excel 关联文件状态
  const [purchaseFiles, setPurchaseFiles] = useState<string[]>([]);
  const [selectedSourceFile, setSelectedSourceFile] = useState<string>('');
  const [selectedTargetFile, setSelectedTargetFile] = useState<string>('');
  const [scannedDir, setScannedDir] = useState<string>('');
  const [scanErrorMessage, setScanErrorMessage] = useState<string>('');
  const [isRefreshingFiles, setIsRefreshingFiles] = useState<boolean>(false);

  // 运行与日志状态
  const [loading, setLoading] = useState<boolean>(false);
  const [logs, setLogs] = useState<ExecutionProgress[]>([]);

  // 动画队列计时器句柄引用，避免组件卸载时内存泄漏
  const timerRefs = useRef<number[]>([]);

  // 清除未完成的动效计时器
  const clearAllTimers = () => {
    timerRefs.current.forEach((id) => clearTimeout(id));
    timerRefs.current = [];
  };

  /**
   * 获取并刷新工作目录下的 Excel 文件列表
   */
  const fetchPurchaseFiles = async () => {
    setIsRefreshingFiles(true);
    try {
      const res = await fetch('/api/purchase/files');
      if (res.ok) {
        const data: PurchaseFilesResponse = await res.json();
        const filesList = data.files || [];
        setPurchaseFiles(filesList);
        if (data.scannedDir) {
          setScannedDir(data.scannedDir);
        }
        if (data.errorMessage) {
          setScanErrorMessage(data.errorMessage);
        } else {
          setScanErrorMessage('');
        }

        if (data.recommendedSource) {
          setSelectedSourceFile((prev) => (prev && filesList.includes(prev) ? prev : data.recommendedSource!));
        } else if (filesList.length > 0) {
          setSelectedSourceFile((prev) => (prev && filesList.includes(prev) ? prev : filesList[0]));
        }

        if (data.recommendedTarget) {
          setSelectedTargetFile((prev) => (prev && filesList.includes(prev) ? prev : data.recommendedTarget!));
        } else if (filesList.length > 1) {
          setSelectedTargetFile((prev) => (prev && filesList.includes(prev) ? prev : filesList[1]));
        }
      }
    } catch (e) {
      console.warn('获取采购单文件列表失败:', e);
    } finally {
      setIsRefreshingFiles(false);
    }
  };

  useEffect(() => {
    fetchPurchaseFiles();
  }, []);

  /**
   * 逐条输出日志动效控制器：
   * 将日志列表以自然的时间间隔（约 260ms）逐条追加滑入
   */
  const streamLogsGradually = (incomingLogs: ExecutionProgress[]) => {
    clearAllTimers();

    incomingLogs.forEach((logItem, index) => {
      const timerId = window.setTimeout(() => {
        setLogs((prev) => [...prev, logItem]);
        // 当最后一条输出完成时解除 loading
        if (index === incomingLogs.length - 1) {
          setLoading(false);
        }
      }, (index + 1) * 260);

      timerRefs.current.push(timerId);
    });
  };

  // 触发订单执行
  const handleExecuteOrder = async () => {
    if (!customer.trim() || !orderNo.trim()) {
      return;
    }

    clearAllTimers();
    setLoading(true);

    // 立即输出第一条初始触发日志
    const fileTypeLabel = orderFileType === 'AUTO' ? '自动检测' : orderFileType === 'PDF' ? 'PDF 单据' : 'PNG 图片';
    const initialLog: ExecutionProgress = {
      stageCode: 'CLIENT_TRIGGER',
      stageName: '用户发起执行',
      message: `正在连接后端服务，提交客户【${customer.trim()}】与订单号【${orderNo.trim()}】${checkTotalAmount.trim() ? `，校验总金额【${checkTotalAmount.trim()}】` : ''
        }，单据格式【${fileTypeLabel}】...`,
      status: 'RUNNING',
      timestamp: new Date().toLocaleTimeString('zh-CN'),
    };
    setLogs([initialLog]);

    try {
      const response = await fetch('/api/order/execute', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          customer: customer.trim(),
          orderNo: orderNo.trim(),
          checkTotalAmount: checkTotalAmount.trim(),
          fileType: orderFileType,
        }),
      });

      const data: OrderExecuteResponse = await response.json();

      if (data.logs && data.logs.length > 0) {
        streamLogsGradually(data.logs);
      } else {
        const fallbackLog: ExecutionProgress = {
          stageCode: 'SERVER_RESULT',
          stageName: '后端响应',
          message: data.message,
          status: data.success ? 'SUCCESS' : 'FAILED',
          timestamp: new Date().toLocaleTimeString('zh-CN'),
        };
        streamLogsGradually([fallbackLog]);
      }
    } catch (err: any) {
      const errorLog: ExecutionProgress = {
        stageCode: 'NETWORK_ERROR',
        stageName: '服务连接异常',
        message: `无法连接到本地后端服务 (http://localhost:8080)，请确认 Spring 后端是否已启动。错误详情: ${err.message}`,
        status: 'FAILED',
        timestamp: new Date().toLocaleTimeString('zh-CN'),
      };
      streamLogsGradually([errorLog]);
    }
  };

  // 触发采购单执行
  const handleExecutePurchase = async () => {
    if (!purchaseDate.trim() || !supplier.trim() || !purchaseOrderNo.trim()) {
      return;
    }

    if (!selectedSourceFile || !selectedTargetFile) {
      alert('请先选择采购源单据与目标母表 Excel 文件。');
      return;
    }

    if (selectedSourceFile === selectedTargetFile) {
      alert('采购源单据与目标母表不能是同一个文件，请分别选择对应文件。');
      return;
    }

    clearAllTimers();
    setLoading(true);

    const initialLog: ExecutionProgress = {
      stageCode: 'CLIENT_TRIGGER',
      stageName: '发起采购单处理',
      message: `正在连接后端服务，提交日期【${purchaseDate.trim()}】、供应商【${supplier.trim()}】、采购单号【${purchaseOrderNo.trim()}】、源单据【${selectedSourceFile}】、目标母表【${selectedTargetFile}】${
        checkTotalQuantity.trim() ? `，校验总数量【${checkTotalQuantity.trim()}】` : ''
      }${
        purchaseCheckTotalAmount.trim() ? `，校验总金额【${purchaseCheckTotalAmount.trim()}】` : ''
      }...`,
      status: 'RUNNING',
      timestamp: new Date().toLocaleTimeString('zh-CN'),
    };
    setLogs([initialLog]);

    try {
      const response = await fetch('/api/purchase/execute', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          date: purchaseDate.trim(),
          supplier: supplier.trim(),
          purchaseOrderNo: purchaseOrderNo.trim(),
          checkTotalQuantity: checkTotalQuantity.trim(),
          checkTotalAmount: purchaseCheckTotalAmount.trim(),
          sourceFileName: selectedSourceFile,
          targetFileName: selectedTargetFile,
        }),
      });

      const data: OrderExecuteResponse = await response.json();

      if (data.logs && data.logs.length > 0) {
        streamLogsGradually(data.logs);
      } else {
        const fallbackLog: ExecutionProgress = {
          stageCode: 'SERVER_RESULT',
          stageName: '后端响应',
          message: data.message,
          status: data.success ? 'SUCCESS' : 'FAILED',
          timestamp: new Date().toLocaleTimeString('zh-CN'),
        };
        streamLogsGradually([fallbackLog]);
      }
    } catch (err: any) {
      const errorLog: ExecutionProgress = {
        stageCode: 'NETWORK_ERROR',
        stageName: '服务连接异常',
        message: `无法连接到本地后端服务 (http://localhost:8080)，请确认 Spring 后端是否已启动。错误详情: ${err.message}`,
        status: 'FAILED',
        timestamp: new Date().toLocaleTimeString('zh-CN'),
      };
      streamLogsGradually([errorLog]);
    }
  };

  return (
    <div>
      {/* 顶部标题栏（日式小清新排版） */}
      <header className="header-bar">
        <div className="brand-title">
          <span>疾风野猪系统</span>
          <span className="brand-tag">v1.0 单据处理台</span>
        </div>
        <div className="header-status">
          <span>工作空间就绪 · 纯离线安全运行</span>
        </div>
      </header>

      {/* 主界面：左侧日志监控，右侧功能卡片网格 */}
      <main className="main-layout">
        {/* 左侧：Log 日志大面板 */}
        <LogPanel logs={logs} loading={loading} />

        {/* 右侧：功能模块网格 */}
        <section className="modules-column">
          {INITIAL_MODULES.map((mod) => {
            if (mod.id === 'order') {
              return (
                <OrderModuleCard
                  key={mod.id}
                  customer={customer}
                  orderNo={orderNo}
                  checkTotalAmount={checkTotalAmount}
                  fileType={orderFileType}
                  loading={loading}
                  onCustomerChange={setCustomer}
                  onOrderNoChange={setOrderNo}
                  onCheckTotalAmountChange={setCheckTotalAmount}
                  onFileTypeChange={setOrderFileType}
                  onExecute={handleExecuteOrder}
                />
              );
            }
            if (mod.id === 'purchase') {
              return (
                <PurchaseModuleCard
                  key={mod.id}
                  date={purchaseDate}
                  supplier={supplier}
                  purchaseOrderNo={purchaseOrderNo}
                  checkTotalQuantity={checkTotalQuantity}
                  checkTotalAmount={purchaseCheckTotalAmount}
                  files={purchaseFiles}
                  sourceFile={selectedSourceFile}
                  targetFile={selectedTargetFile}
                  scannedDir={scannedDir}
                  scanErrorMessage={scanErrorMessage}
                  isRefreshingFiles={isRefreshingFiles}
                  loading={loading}
                  onDateChange={setPurchaseDate}
                  onSupplierChange={setSupplier}
                  onPurchaseOrderNoChange={setPurchaseOrderNo}
                  onCheckTotalQuantityChange={setCheckTotalQuantity}
                  onCheckTotalAmountChange={setPurchaseCheckTotalAmount}
                  onSourceFileChange={setSelectedSourceFile}
                  onTargetFileChange={setSelectedTargetFile}
                  onRefreshFiles={fetchPurchaseFiles}
                  onExecute={handleExecutePurchase}
                />
              );
            }
            return <PlaceholderCard key={mod.id} module={mod} />;
          })}
        </section>
      </main>
    </div>
  );
};

export default App;
