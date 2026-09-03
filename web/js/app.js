/**
 * 应用编排层
 * ============================================================
 * 负责状态管理、文件上传解析、事件绑定与整体渲染协调。
 * 依赖 AppConfig / AppUtil / AppData / AppChart（按加载顺序存在）。
 */
(function () {
  "use strict";

  const CONFIG = window.AppConfig;
  const UTIL = window.AppUtil;
  const DATA = window.AppData;
  const CHART = window.AppChart;

  /** @type {Object} 应用状态 */
  const state = {
    outputData: null, // 优化后方案 output.txt 解析结果
    topoData: null,   // 优化前整车拓扑 json 解析结果
    level: "system",
    metric: null,
    ascending: false,
    filter: new Set(),        // 当前选中的条目名称集合（空/全量 = 显示全部）
    filterNames: [],          // 当前层级全部条目名称（用于计数与全选）
    filterQuery: "",          // 筛选搜索关键字
    detail: null              // 当前点开的回路明细：{ name, id }，null 表示未点开
  };

  /** @type {Object<string, HTMLElement>} DOM 缓存 */
  const ui = {};

  /**
   * 缓存常用 DOM 元素，避免重复查询。
   */
  function cacheDom() {
    const ids = [
      "analysis", "level-seg", "sort-seg", "metric-select", "summary-line",
      "chart-card", "chart-title", "chart-inner", "project-card", "project-grid",
      "badge-output", "badge-topo", "file-name-output", "file-name-topo",
      "drop-error-output", "drop-error-topo", "topo-summary",
      "drop-zone-output", "drop-zone-topo", "file-input-output", "file-input-topo",
      "filter-toggle", "filter-badge", "filter-dropdown", "filter-search",
      "filter-all", "filter-clear", "filter-list",
      "detail-card", "detail-title", "detail-table", "detail-thead", "detail-tbody", "detail-close"
    ];
    ids.forEach((id) => { ui[id] = document.getElementById(id); });
  }

  /* ---------------- 层级切换控件（由配置动态生成） ---------------- */

  /**
   * 生成层级分段按钮。
   */
  function buildLevelSegments() {
    const html = CONFIG.LEVELS.map(function (level) {
      const active = level.key === state.level ? " is-active" : "";
      return '<button type="button" data-level="' + level.key + '"' +
        ' class="seg-option' + active + '">' + level.label + "</button>";
    }).join("");
    ui["level-seg"].innerHTML = html;
  }

  /**
   * 获取当前层级的默认指标。
   * @returns {string} 默认指标字段名
   */
  function getLevelDefaultMetric() {
    const found = CONFIG.LEVELS.find(function (level) { return level.key === state.level; });
    return found ? found.defaultMetric : CONFIG.DEFAULT_METRIC;
  }

  /**
   * 填充指标下拉框，尽量保留当前选中值，否则回落「层级默认 → 全局默认 → 首个」。
   * @param {string} [prefer] 优先保留的指标名
   */
  function populateMetricSelect(prefer) {
    const options = DATA.getMetricOptions(state.level, state.outputData);
    const candidates = [prefer, getLevelDefaultMetric(), CONFIG.DEFAULT_METRIC, options[0]];
    let target = null;
    for (let i = 0; i < candidates.length; i++) {
      if (candidates[i] !== null && candidates[i] !== undefined && options.indexOf(candidates[i]) >= 0) {
        target = candidates[i];
        break;
      }
    }
    ui["metric-select"].innerHTML = options.map(function (key) {
      const selected = key === target ? " selected" : "";
      return '<option value="' + UTIL.escapeHtml(key) + '"' + selected + ">" +
        UTIL.escapeHtml(key) + "</option>";
    }).join("");
    state.metric = target;
  }

  /* ---------------- 条目筛选 ---------------- */

  /**
   * 获取当前层级的全部条目名称（唯一、有序）。
   * @returns {string[]} 名称列表
   */
  function getLevelNames() {
    if (state.level === "project") return [];
    return DATA.getEntries(state.level, state.outputData).map(function (entry) {
      return entry.name;
    });
  }

  /**
   * 依据当前层级重建筛选列表：默认全选，并渲染勾选框。
   */
  function buildFilterList() {
    state.filterNames = getLevelNames();
    state.filter = new Set(state.filterNames);
    state.filterQuery = "";
    ui["filter-search"].value = "";
    renderFilterCheckboxes();
    updateFilterBadge();
  }

  /**
   * 渲染筛选勾选列表（支持关键字过滤）。
   */
  function renderFilterCheckboxes() {
    const query = state.filterQuery.trim().toLowerCase();
    const visible = state.filterNames.filter(function (name) {
      return !query || name.toLowerCase().indexOf(query) >= 0;
    });
    const html = visible.length === 0
      ? '<div class="filter-empty">无匹配项</div>'
      : visible.map(function (name) {
        const checked = state.filter.has(name) ? " checked" : "";
        return (
          '<label class="filter-item">' +
          '<input type="checkbox" value="' + UTIL.escapeHtml(name) + '"' + checked + ">" +
          '<span class="filter-item-text" title="' + UTIL.escapeHtml(name) + '">' +
          UTIL.escapeHtml(name) + "</span>" +
          "</label>"
        );
      }).join("");
    ui["filter-list"].innerHTML = html;
  }

  /**
   * 更新筛选按钮上的计数徽章：仅在非全选时显示「已选/总数」。
   */
  function updateFilterBadge() {
    const total = state.filterNames.length;
    const selected = state.filter.size;
    const badge = ui["filter-badge"];
    if (selected >= total) {
      badge.classList.add("hidden");
      badge.textContent = "";
    } else {
      badge.classList.remove("hidden");
      badge.textContent = selected + "/" + total;
    }
  }

  /**
   * 开/关筛选下拉框。
   * @param {boolean} [open] 强制指定开（true）或关（false）；缺省则取反
   */
  function toggleFilterDropdown(open) {
    const dropdown = ui["filter-dropdown"];
    const shouldOpen = open !== undefined ? open : dropdown.classList.contains("hidden");
    dropdown.classList.toggle("hidden", !shouldOpen);
    ui["filter-toggle"].setAttribute("aria-expanded", shouldOpen ? "true" : "false");
    if (shouldOpen) ui["filter-search"].focus();
  }

  /**
   * 依据当前筛选状态过滤条目。
   * @param {Array} entries 全部条目
   * @returns {Array} 过滤后的条目
   */
  function applyFilter(entries) {
    // 全选（选中数 == 总数）时不过滤，直接返回全部
    if (state.filter.size >= state.filterNames.length) return entries;
    return entries.filter(function (entry) {
      return state.filter.has(entry.name);
    });
  }

  /* ---------------- 渲染 ---------------- */

  /**
   * 刷新整块分析区（依据当前层级/指标/排序）。
   */
  function refresh() {
    if (!state.outputData) return;
    const levelName = CONFIG.LEVELS.find(function (l) { return l.key === state.level; }).label;

    if (state.level === "project") {
      renderProjectLevel();
      closeCircuitDetail();
    } else {
      renderBarLevel(levelName);
      // 若有已点开的柱子，随着指标/排序变化同步刷新明细
      if (state.detail) {
        openCircuitDetail(state.detail.name, state.detail.id, false);
      }
    }
  }

  /**
   * 渲染多条目层级的柱状图。
   * @param {string} levelName 层级中文名
   */
  function renderBarLevel(levelName) {
    const allEntries = DATA.getEntries(state.level, state.outputData);
    ui["chart-title"].textContent = levelName + " · " + state.metric + " 柱状图";

    // 应用筛选
    const entries = applyFilter(allEntries);
    const filterActive = state.filter.size < state.filterNames.length;

    ui["project-card"].classList.add("hidden");
    ui["chart-card"].classList.remove("hidden");

    // 筛选后无条目：直接展示空态
    if (entries.length === 0) {
      ui["chart-inner"].innerHTML =
        '<div class="empty">当前条件下无条目可显示（筛选为空或指标缺失）</div>';
      ui["summary-line"].innerHTML =
        '<span class="summary-item">层级：<b>' + levelName + "</b></span>" +
        '<span class="summary-item">指标：<b>' + UTIL.escapeHtml(state.metric) + "</b></span>" +
        '<span class="summary-item">已筛选项：<b>0 / ' + state.filterNames.length + "</b></span>";
      return;
    }

    // 排序
    const sorted = entries.slice().sort(function (a, b) {
      const va = UTIL.toNumber(a.agg ? a.agg[state.metric] : null);
      const vb = UTIL.toNumber(b.agg ? b.agg[state.metric] : null);
      const delta = va - vb;
      return state.ascending ? delta : -delta;
    });

    const result = CHART.renderBars(sorted, state.metric);

    if (result.count > 0) {
      const skipText = result.skipped > 0 ? "（忽略缺失 " + result.skipped + "）" : "";
      const filterText = filterActive
        ? '<span class="summary-item">筛选：<b>' + entries.length + " / " + state.filterNames.length + "</b></span>"
        : "";
      ui["summary-line"].innerHTML =
        '<span class="summary-item">层级：<b>' + levelName + "</b></span>" +
        '<span class="summary-item">指标：<b>' + UTIL.escapeHtml(state.metric) + "</b></span>" +
        '<span class="summary-item">条目数：<b>' + result.count + "</b>" + skipText + "</span>" + filterText +
        '<span class="summary-item">最大：<b>' + UTIL.formatValue(result.max, state.metric) + "</b></span>" +
        '<span class="summary-item">最小：<b>' + UTIL.formatValue(result.min, state.metric) + "</b></span>" +
        '<span class="summary-item">合计：<b>' + UTIL.formatValue(result.sum, state.metric) + "</b></span>";
    }
  }

  /**
   * 渲染整车级指标卡片总览。
   */
  function renderProjectLevel() {
    ui["chart-card"].classList.add("hidden");
    ui["project-card"].classList.remove("hidden");
    CHART.renderProject(state.outputData.projectCircuitInfo || {});

    const fieldCount = Object.keys(state.outputData.projectCircuitInfo || {}).length;
    ui["summary-line"].innerHTML =
      '<span class="summary-item">层级：<b>整车级</b></span>' +
      '<span class="summary-item">指标字段：<b>' + UTIL.escapeHtml(state.metric) + "</b></span>" +
      '<span class="summary-item">整车指标数：<b>' + fieldCount + "</b></span>";
  }

  /* ---------------- 回路明细 ---------------- */

  /**
   * 将可能为 null/undefined 的值安全转为转义后的 HTML 文本。
   * @param {*} value 原始值
   * @returns {string} 转义后文本（空值返回空串）
   */
  function cellText(value) {
    if (value === null || value === undefined) return "";
    return UTIL.escapeHtml(String(value));
  }

  /**
   * 在柱状图中高亮当前点开的柱子。
   * @param {string} name 条目名称
   * @param {string} [id] 回路层唯一 id
   */
  function markSelectedColumn(name, id) {
    const columns = ui["chart-inner"].querySelectorAll(".bar-column");
    columns.forEach(function (col) {
      const matched = id !== null && id !== undefined
        ? col.dataset.id === String(id)
        : col.dataset.name === String(name);
      col.classList.toggle("is-selected", matched);
    });
  }

  /**
   * 渲染回路明细表格：第一列为排序指标值，其后为回路编号/起点/终点/导线选型/所属系统。
   * @param {string} metricField 排序指标字段名
   * @param {Array<Object>} circuits 已排序的回路对象列表
   */
  function renderDetailTable(metricField, circuits) {
    const headers = [metricField, "回路编号", "起点用电器名称", "终点用电器名称", "导线选型", "所属系统"];
    ui["detail-thead"].innerHTML = headers.map(function (h) {
      return "<th>" + UTIL.escapeHtml(h) + "</th>";
    }).join("");

    if (circuits.length === 0) {
      ui["detail-tbody"].innerHTML =
        '<tr><td colspan="' + headers.length + '" class="detail-empty">该条目没有关联回路</td></tr>';
      return;
    }

    ui["detail-tbody"].innerHTML = circuits.map(function (circuit) {
      const raw = UTIL.toNumber(circuit[metricField]);
      const metricText = Number.isNaN(raw) ? "—" : UTIL.formatValue(raw, metricField);
      return "<tr>" +
        '<td class="cell-num">' + metricText + "</td>" +
        "<td>" + cellText(circuit["回路编号"]) + "</td>" +
        "<td>" + cellText(circuit["起点用电器名称"]) + "</td>" +
        "<td>" + cellText(circuit["终点用电器名称"]) + "</td>" +
        "<td>" + cellText(circuit["导线选型"]) + "</td>" +
        "<td>" + cellText(circuit["所属系统"]) + "</td>" +
        "</tr>";
    }).join("");
  }

  /**
   * 打开某个柱子的回路明细（获取该条目关联回路，按当前指标与排序方向排序展示）。
   * @param {string} name 条目名称
   * @param {string} [id] 回路层唯一 id
   * @param {boolean} [doScroll] 是否滚动到明细卡片（仅在用户点击时触发）
   */
  function openCircuitDetail(name, id, doScroll) {
    if (!state.outputData) return;
    state.detail = { name: name, id: id };

    const circuits = DATA.getCircuitsForEntry(state.outputData, state.level, name, id);
    const metricField = DATA.resolveCircuitMetric(state.metric, state.outputData);

    // 按指标值排序（缺失值视为 -Infinity，升序时沉底、降序时沉底）
    circuits.sort(function (a, b) {
      const va = UTIL.toNumber(a[metricField]);
      const vb = UTIL.toNumber(b[metricField]);
      const na = Number.isNaN(va) ? -Infinity : va;
      const nb = Number.isNaN(vb) ? -Infinity : vb;
      return state.ascending ? na - nb : nb - na;
    });

    ui["detail-title"].textContent = "回路明细 · " + String(name).replace(/\n/g, " ");
    markSelectedColumn(name, id);
    renderDetailTable(metricField, circuits);
    ui["detail-card"].classList.remove("hidden");

    if (doScroll) {
      ui["detail-card"].scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  }

  /**
   * 关闭回路明细卡片并清除柱子高亮。
   */
  function closeCircuitDetail() {
    state.detail = null;
    ui["detail-card"].classList.add("hidden");
    ui["chart-inner"].querySelectorAll(".bar-column").forEach(function (col) {
      col.classList.remove("is-selected");
    });
  }

  /* ---------------- 文件上传与解析 ---------------- */

  /**
   * 绑定上传区域（点击/拖拽/键盘）。
   * @param {string} zoneId 上传区域 DOM id
   * @param {string} inputId 隐藏 file input DOM id
   * @param {Function} handler 文件处理回调，入参为 File
   */
  function bindUpload(zoneId, inputId, handler) {
    const zone = ui[zoneId];
    const input = ui[inputId];
    const doLoad = function (file) {
      if (file) handler(file);
    };
    zone.addEventListener("click", function () { input.click(); });
    zone.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        input.click();
      }
    });
    zone.addEventListener("dragover", function (event) {
      event.preventDefault();
      zone.classList.add("is-drag");
    });
    zone.addEventListener("dragleave", function () { zone.classList.remove("is-drag"); });
    zone.addEventListener("drop", function (event) {
      event.preventDefault();
      zone.classList.remove("is-drag");
      doLoad(event.dataTransfer.files[0]);
    });
    input.addEventListener("change", function () { doLoad(input.files[0]); });
  }

  /**
   * 以文本方式读取文件。
   * @param {File} file 文件对象
   * @returns {Promise<string>} 文件文本
   */
  function readAsText(file) {
    return new Promise(function (resolve, reject) {
      const reader = new FileReader();
      reader.onload = function () { resolve(reader.result); };
      reader.onerror = function () { reject(new Error("文件读取失败")); };
      reader.readAsText(file);
    });
  }

  /**
   * 以二进制方式读取文件（供 Excel 解析使用）。
   * @param {File} file 文件对象
   * @returns {Promise<ArrayBuffer>} 文件内容
   */
  function readAsArrayBuffer(file) {
    return new Promise(function (resolve, reject) {
      const reader = new FileReader();
      reader.onload = function () { resolve(reader.result); };
      reader.onerror = function () { reject(new Error("文件读取失败")); };
      reader.readAsArrayBuffer(file);
    });
  }

  /**
   * 校验并解析生产环境 Excel（转成与 output.json 相同结构）。
   * @param {File} file 文件对象
   * @returns {Promise<Object>} 解析后的数据对象
   * @throws {Error} 缺解析库或结构不合法时抛出
   */
  async function parseExcel(file) {
    if (!window.XLSX) {
      throw new Error("缺少 XLSX 解析库，请确认已加载 lib/xlsx.full.min.js");
    }
    const buffer = await readAsArrayBuffer(file);
    const workbook = window.XLSX.read(buffer, { type: "array" });
    return window.AppExcel.buildHierarchy(workbook);
  }

  /**
   * 校验并解析 output.txt（优化后方案）。
   * @param {string} text 文件文本
   * @returns {Object} 解析后的数据对象
   * @throws {Error} 结构不合法时抛出
   */
  function parseOutput(text) {
    const data = JSON.parse(text);
    if (!data.systemCircuitInfo) {
      throw new Error("缺少 systemCircuitInfo 字段，不是有效的 output 方案 JSON");
    }
    return data;
  }

  /**
   * 校验并解析拓扑日志文件（首份文档后可能追加残缺片段，做容错）。
   * @param {string} text 文件文本
   * @returns {Object} 解析后的数据对象
   * @throws {Error} 结构不合法时抛出
   */
  function parseTopo(text) {
    const doc = UTIL.extractFirstJson(text) || text;
    const data = JSON.parse(doc);
    if (!data.points || !data.edges) {
      throw new Error("缺少 points/edges 字段，不是有效的拓扑 JSON");
    }
    return data;
  }

  /**
   * 加载并接入 output 方案数据。
   * 支持两种来源：Excel（.xlsx/.xls，生产环境导出，解析为与 output.json 相同结构）
   * 或 output.txt/.json（原优化后方案）。
   * @param {File} file 文件对象
   */
  async function loadOutput(file) {
    try {
      const isExcel = /\.(xlsx|xls)$/i.test(file.name || "");
      state.outputData = isExcel
        ? await parseExcel(file)
        : parseOutput(await readAsText(file));
      updateDropZone("output", file, "已加载", "is-ok", "");
      populateMetricSelect(null);
      buildFilterList();
      closeCircuitDetail();
      ui.analysis.classList.remove("hidden");
      refresh();
    } catch (error) {
      state.outputData = null;
      updateDropZone("output", file, "解析失败", "is-wait", error.message);
    }
  }

  /**
   * 加载并接入拓扑日志文件。
   * @param {File} file 文件对象
   */
  async function loadTopo(file) {
    try {
      const text = await readAsText(file);
      state.topoData = parseTopo(text);
      updateDropZone("topo", file, "已加载", "is-ok", "");
      const caseName = state.topoData.caseInfo && state.topoData.caseInfo.caseName
        ? state.topoData.caseInfo.caseName : "-";
      ui["topo-summary"].classList.remove("hidden");
      ui["topo-summary"].innerHTML =
        "拓扑信息：<b>" + UTIL.escapeHtml(caseName) + "</b> · " +
        "支点 <b>" + state.topoData.points.length + "</b> 个 · " +
        "分支 <b>" + state.topoData.edges.length + "</b> 条 · " +
        "回路 <b>" + (state.topoData.loopInfos ? state.topoData.loopInfos.length : 0) + "</b> 条 · " +
        "用电器 <b>" + (state.topoData.appPositions ? state.topoData.appPositions.length : 0) +
        "</b> 个（坐标已就绪，后续拓扑画图使用）";
    } catch (error) {
      state.topoData = null;
      updateDropZone("topo", file, "解析失败", "is-wait", error.message);
    }
  }

  /**
   * 更新上传区域的状态展示。
   * @param {string} key "output" | "topo"
   * @param {File} file 文件对象
   * @param {string} badgeText 徽章文本
   * @param {string} badgeClass 徽章样式类
   * @param {string} errorText 错误文本（可为空）
   */
  function updateDropZone(key, file, badgeText, badgeClass, errorText) {
    const badge = ui["badge-" + key];
    badge.textContent = badgeText;
    badge.className = "badge " + badgeClass;
    ui["file-name-" + key].textContent = file.name + "（" + (file.size / 1024).toFixed(0) + " KB）";
    ui["drop-error-" + key].textContent = errorText;
  }

  /* ---------------- 事件装配 ---------------- */

  /**
   * 绑定各交互控件的点击/变更事件。
   */
  function bindEvents() {
    // 层级切换
    ui["level-seg"].addEventListener("click", function (event) {
      const button = event.target.closest("button[data-level]");
      if (!button) return;
      state.level = button.dataset.level;
      document.querySelectorAll("#level-seg button").forEach(function (btn) {
        btn.classList.toggle("is-active", btn === button);
      });
      populateMetricSelect(state.metric);
      buildFilterList();
      closeCircuitDetail();
      refresh();
    });

    // 排序方向切换
    ui["sort-seg"].addEventListener("click", function (event) {
      const button = event.target.closest("button[data-asc]");
      if (!button) return;
      state.ascending = button.dataset.asc === "true";
      document.querySelectorAll("#sort-seg button").forEach(function (btn) {
        btn.classList.toggle("is-active", btn === button);
      });
      refresh();
    });

    // 指标切换
    ui["metric-select"].addEventListener("change", function () {
      state.metric = ui["metric-select"].value;
      refresh();
    });

    // 筛选按钮：开/关下拉框
    ui["filter-toggle"].addEventListener("click", function () {
      toggleFilterDropdown();
    });
    // 点击筛选区域外时关闭下拉框
    document.addEventListener("click", function (event) {
      const wrap = ui["filter-toggle"].closest(".filter-wrap");
      if (wrap && !wrap.contains(event.target)) {
        toggleFilterDropdown(false);
      }
    });
    // 搜索关键字
    ui["filter-search"].addEventListener("input", function () {
      state.filterQuery = ui["filter-search"].value;
      renderFilterCheckboxes();
    });
    // 全选
    ui["filter-all"].addEventListener("click", function () {
      state.filter = new Set(state.filterNames);
      renderFilterCheckboxes();
      updateFilterBadge();
      refresh();
    });
    // 清空
    ui["filter-clear"].addEventListener("click", function () {
      state.filter = new Set();
      renderFilterCheckboxes();
      updateFilterBadge();
      refresh();
    });
    // 勾选/取消勾选（事件委托）
    ui["filter-list"].addEventListener("change", function (event) {
      const checkbox = event.target.closest('input[type="checkbox"]');
      if (!checkbox) return;
      const name = checkbox.value;
      if (checkbox.checked) state.filter.add(name);
      else state.filter.delete(name);
      updateFilterBadge();
      refresh();
    });

    // 点击柱状图柱子：展开该条目的回路明细
    ui["chart-inner"].addEventListener("click", function (event) {
      const column = event.target.closest(".bar-column");
      if (!column) return;
      const name = column.dataset.name || "";
      const id = column.dataset.id || null;
      openCircuitDetail(name, id, true);
    });

    // 关闭回路明细
    ui["detail-close"].addEventListener("click", closeCircuitDetail);

    bindUpload("drop-zone-output", "file-input-output", function (file) { loadOutput(file); });
    bindUpload("drop-zone-topo", "file-input-topo", function (file) { loadTopo(file); });
  }

  /* ---------------- 启动 ---------------- */

  /**
   * 应用入口：缓存 DOM、生成控件、绑定事件。
   */
  function init() {
    cacheDom();
    buildLevelSegments();
    bindEvents();
    CHART.initTooltip();
  }

  document.addEventListener("DOMContentLoaded", init);
})();