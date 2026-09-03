# Web 端支持上传 Excel 并复用 output JSON 可视化

## Context（背景与目标）

现有 `web` 是纯静态页面（无后端服务器），`output.txt/output.json` 由前端直接
`JSON.parse` 后，按 6 个顶层键消费并绘制统计图表（系统/用电器/接口/分支/回路/整车层级 +
点击柱体查看回路明细 + 整车指标卡片）。

业务需求已变更：**不再上传 output.json，而是上传生产环境导出的 Excel**（
`工具计算结果demo用于量化参数可视化测试.xlsx`，含 6 张工作表）。Excel 的内容与 output
本质相同，只是把嵌套聚合结构平铺、并把每级预计算结果做成"汇总行"。

目标：**上传 xlsx 后，前端解析并重建出与 output.json 相同顶层结构的数据，复用现有渲染逻辑**。
已确认：解析放在前端浏览器（引入 SheetJS），指标表头映射为 output.json 的键名。

## Excel → 结构映射（关键）

6 张工作表的表头为多层（1\~4 行），统一检测规则：

- 表头行 = 某行第 1 列为 `方案名称` 的那一行。

- 数据行起点 = 表头行之后、第 1 列为非空且不为 `方案名称` 的首行（各表数据行第 1 列均为方案名，
  如 `配电驱动功能分配测试案例`；层级子表头行第 1 列为空，可安全跳过）。

各表与 output 顶层键的对应：

| 工作表      | output 键                          | 组成方式                                                                                    |
| -------- | --------------------------------- | --------------------------------------------------------------------------------------- |
| 每一条回路信息  | `circuitInfo[]`                   | 每个数据行 = 一条回路对象；`回路id` 取 `回路编号`（生产模板无独立 id 列）                                            |
| 整车级回路信息  | `projectCircuitInfo`              | 唯一数据行 → 指标对象                                                                            |
| 系统级回路信息  | `systemCircuitInfo`               | 汇总行（`回路编号=="系统内所有回路"`，名=所属系统）→ `circuitInfoIntergation`；其后的明细行 `回路编号` 收集进 `circuitList` |
| 用电器级回路信息 | `elecRelatedCircuitInfo`          | 汇总行（`名称=="用电器相关所有回路"`，名=相关用电器）                                                          |
| 接口级回路信息  | `elecInterfaceRelatedCircuitInfo` | 汇总行（`=="接口相关所有回路"`），按 `{用电器:{接口:{circuitInfoIntergation,circuitList}}}` 嵌套              |
| 分支级回路信息  | `bundeleRelatedCircuitInfo`       | 汇总行（`=="分支相关所有回路"`，名=相关分支名称）                                                            |

指标字段命名：新增 `web/js/excel.js` 中定义每张表的 `COLUMN_MAP`（下标 → output 键名），
把 `回路总重量（克）→ 回路总重量`、`导线成本 → 回路导线总成本`、`端子成本 → 端子总成本`、
`连接器塑壳成本 → 连接器塑壳总成本`、`防水塞成本 → 防水塞总成本`、
`回路数量-A类（根）→ 回路数量-A类(根)` 等统一映射，使现有默认指标（`回路总重量`）与下拉逻辑无需改动。
单回路字段映射到现有 `data.js` 的 `AGG_TO_CIRCUIT_METRIC` 取值（`回路重量/回路长度/回路总成本/回路导线成本/端子成本/连接器塑壳成本/防水塞成本`），保证明细排序正常。

`circuitList` 元素与 `circuitInfo[].回路id` 统一用 `回路编号`（统一转字符串），与
`data.js` 中 `buildCircuitMap`/`getCircuitsForEntry` 的按 id 反查逻辑对齐。

## 改动清单

### 1. 新增 `web/lib/xlsx.full.min.js`（SheetJS）

- 从官方 CDN 下载全文保存到该路径（实现时用 WebFetch 拉取后写入）。

- 纯前端解析依赖，本地化避免离线/外网依赖。

### 2. 新增 `web/js/excel.js`

- 暴露 `window.AppExcel = { buildHierarchy(workbook) }`。

- 内置各 sheet 的 `COLUMN_MAP`（下标 → output 键名）与表头/数据行检测。

- 解析并产出 `{ systemCircuitInfo, elecRelatedCircuitInfo, elecInterfaceRelatedCircuitInfo, bundeleRelatedCircuitInfo, circuitInfo, projectCircuitInfo }`。

- 宽容处理：缺失个别 sheet 时跳过该层级；至少生成 `systemCircuitInfo` 与 `circuitInfo`，否则抛出可读错误。

- 单元格数值/字符串百分比（如 `"50.68%"`、`"1709"`）原样保留，复用现有 `util.toNumber`/`formatValue` 解析，无需转换。

### 3. 修改 `web/js/app.js`

- 新增 `readAsArrayBuffer(file)`；`loadOutput` 按文件扩展名 `.xlsx/.xls` 走
  `XLSX.read(buffer,{type:'array'})` → `AppExcel.buildHierarchy(workbook)`，`.json/.txt` 走原 `parseOutput`。

- 其余渲染流程（`populateMetricSelect/buildFilterList/refresh`）完全复用，无需改。

### 4. 修改 `web/index.html`

- output 上传区 `accept` 增加 `,.xlsx,.xls`，提示文案改为"优化后方案 JSON（output.txt）**或 Excel (.xlsx)**"。

- 在 `app.js` 之前按依赖顺序引入 `lib/xlsx.full.min.js`、`js/excel.js`。

## 复用的现有能力

- [data.js](f:/office/idearProjects/project20251009/web/js/data.js)：各级 `getEntries`/`getCircuitsForEntry`/`buildCircuitMap`/`resolveCircuitMetric`（契约不变）。

- [chart.js](f:/office/idearProjects/project20251009/web/js/chart.js)：柱状图与整车卡片渲染（契约不变）。

- [util.js](f:/office/idearProjects/project20251009/web/js/util.js)：`toNumber/formatValue/isNumericLike`（用于百分比/字符串数值）。

## 验证

1. 本地打开 `web/index.html`（file:// 或任意静态服务器）。
2. 上传生产 Excel `工具计算结果demo用于量化参数可视化测试.xlsx`，确认 output 上传区显示"已加载"。
3. 依次切换系统级/用电器级/接口级/分支级，确认柱状图与汇总指标正常；指标下拉含 `回路总重量` 且默认可选。
4. 点击柱体打开回路明细，确认按指标排序、字段（编号/起点/终点/导线选型/所属系统）正确。
5. 切换整车级，确认指标卡片渲染。
6. 仍上传原 output.txt/.json，确认旧路径不受影响（向后兼容）。

