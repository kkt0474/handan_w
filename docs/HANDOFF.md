# 项目交接与上下文恢复文档 (HANDOFF.md)

> 本文档用于跨会话上下文归档与恢复（通过 `/save` 生成，通过 `/resume` 恢复）。  
> 归档时间：2026-10-21  
> 项目名称：handan_w (智能单据处理与 Excel 事务引擎)

---

## 1. 需求说明 (Requirements Summary)

### 1.1 业务背景与系统目标
本项目为一个单据智能自动化解析与 Excel 数据追加引擎（含 Web 管理后台与长任务进度监控）。系统支持：
1. **订单模块（Order Module）**：自动解析客户提供的 PDF 单据或 PNG 单据（基于 OCR 与水平投影网格算法），将表格数据规整后追加汇总到目标 Excel 母表中。
2. **采购单模块（Purchase Module）**：从供应商或 ERP 导出的源 Excel 单据（Source）抽取有效物料行（包含点号千分位纠偏、单价与数量计算），汇总聚合后追加写入采购目标 Excel 母表（Target），支持总数量与总金额前台防呆校验与金额差异标红。

### 1.2 核心业务规则与格式要求
1. **去硬编码与配置化过滤规则（最新）**：
   - **规则外部配置化**：通过 `application.yml` 中的 `handan.excel.filter` 配置项统一定义 Excel 文件的过滤规则，彻底杜绝在代码中硬编码任何过滤逻辑：
     - `exclude-prefixes`: 排除前缀（如 `~$`、`.`）
     - `exclude-contains`: 排除包含字符串（如 `_temp_`、`_bak`）
     - `exclude-suffixes`: 排除后缀（如 `.tmp`）
     - `exclude-regex-patterns`: 正则排除规则（如 `.*_\\d{14}\\.(xlsx|xls)$` 时间戳备份）
   - **采购单模块下拉与自动定位统一联动**：
     - 前端下拉框和后端文件扫描统一应用该规则，精准排除时间戳备份文件、临时写回文件及锁文件。
     - 若用户需要放行或调整某种命名规则，直接修改 `application.yml` 即可实时生效。
2. **严格格式校验与防呆报错（方案 2）**：
   - 采购源文件校验：必须包含 `No` 与 `P/N` 标准表头，否则抛出异常并在前端提示 `【采购源文件格式错误 ❌】`。
   - 目标母表校验：必须包含 `【采购单】` 工作表及标准采购表头列，否则抛出异常并在前端提示 `【目标母表格式错误 ❌】`。
   - 同名冲突校验：源单与目标母表若选择相同文件，立即抛出 `【文件选择冲突 ❌】` 提示。
3. **数据末尾真实判定与公式/样式双写**：
   - 严格以第 1 列是否有真实业务数据判定物理行末尾，跳过幽灵底色行。
   - “金额”列写入动态联动公式（如 `=E2*F2`）并同步缓存格式化数值，差异行高保真标红（`IndexedColors.RED`）。
4. **事务性落盘与独占锁防御**：
   - 检测 Office/WPS 锁定文件（`~$<文件名>`）与操作系统文件独占锁。
   - 写前自动生成精确到秒的时间戳备份（`<baseName>_yyyyMMddHHmmss.<ext>`）。
   - 临时文件写入与原子替换（`Files.move REPLACE_EXISTING`）。

---

## 2. 当前进度与已验证功能 (Progress & Verification)

### 2.1 当前已完成并验证的功能与测试
- [x] **32 项单元测试 100% 绿灯通过**：`mvn clean test` 全部通过（包含配置化过滤规则、时间戳过滤、自定义文件名、多文件冲突检测、反选/错选格式校验、同名冲突校验等测试）。
- [x] **前端编译 100% 成功**：`npm run build --prefix frontend`（Vite + React + TypeScript）无任何告警与报错。
- [x] **前端交互式文件选择组件**：
  - `PurchaseModuleCard.tsx` 集成关联 Excel 选择区（采购源单据下拉 + 目标母表下拉 + 刷新文件列表按钮 + 目录路径提示）。
  - `App.tsx` 页面加载时自动请求 `/api/purchase/files` 预填充智能推荐项。
  - 发生任何错误时，通过 `OrderExecuteResponse.failure` 及 `ExecutionProgress.failed` 在左侧日志面板实时高亮显示红色错误标签与详细指导信息。

---

## 3. 关键代码资产 (Key Code Assets)

- **配置驱动过滤体系**：
  - [`src/main/resources/application.yml`](file:///Users/dean/Projects/handan_w/src/main/resources/application.yml)：`handan.excel.filter` 集中管理排除前缀、关键字、后缀和正则。
  - [`src/main/java/com/handan/config/ExcelFilterProperties.java`](file:///Users/dean/Projects/handan_w/src/main/java/com/handan/config/ExcelFilterProperties.java)：Spring Boot 配置属性映射与 `shouldExclude` 判定逻辑。
- **采购单核心服务**：
  - [`src/main/java/com/handan/purchase/PurchaseProcessor.java`](file:///Users/dean/Projects/handan_w/src/main/java/com/handan/purchase/PurchaseProcessor.java)：采购流程编排、源单据与母表前置校验。
  - [`src/main/java/com/handan/purchase/PurchaseReader.java`](file:///Users/dean/Projects/handan_w/src/main/java/com/handan/purchase/PurchaseReader.java)：采购源 Excel 结构化解析，表头缺失与格式错误拦截。
  - [`src/main/java/com/handan/excel/ExcelAppendService.java`](file:///Users/dean/Projects/handan_w/src/main/java/com/handan/excel/ExcelAppendService.java)：`validatePurchaseTargetWorkbook` 目标母表结构合法性校验与原子安全追加。
- **Web 控制层与接口**：
  - [`src/main/java/com/handan/web/PurchaseController.java`](file:///Users/dean/Projects/handan_w/src/main/java/com/handan/web/PurchaseController.java)：`/api/purchase/files` 文件列表与推荐识别接口，`/api/purchase/execute` 执行入口。
  - [`src/main/java/com/handan/web/PurchaseFilesResponse.java`](file:///Users/dean/Projects/handan_w/src/main/java/com/handan/web/PurchaseFilesResponse.java)：文件列表与智能推荐 DTO。
- **前端交互组件**：
  - [`frontend/src/components/PurchaseModuleCard.tsx`](file:///Users/dean/Projects/handan_w/frontend/src/components/PurchaseModuleCard.tsx)：采购单卡片、文件选择下拉框、防呆提示与刷新。
  - [`frontend/src/App.tsx`](file:///Users/dean/Projects/handan_w/frontend/src/App.tsx)：全局状态管理、文件列表获取与执行调度。
