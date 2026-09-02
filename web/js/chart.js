/**
 * 图表渲染层
 * ============================================================
 * 负责柱状图的 DOM 构建与布局，以及整车级指标卡片渲染。
 * 柱状图特性：
 *   - 柱体自下而上生长动画（scaleY），逐柱错峰
 *   - 每个柱的数值标签紧贴各自柱顶上方（y 动态计算）
 *   - 底部名称水平显示，超出省略号，悬停看全名
 *   - 柱宽随条目数量自适应
 * 通过 window.AppChart 暴露。
 */
(function (root) {
  "use strict";

  const util = root.AppUtil;
  const config = root.AppConfig;

  const CHART = config.CHART;

  /**
   * 柱体颜色：按排名在蓝→绿 之间渐变。
   * @param {number} index 排名（0 起）
   * @param {number} total 总数
   * @returns {string} css 颜色字符串
   */
  function barColor(index, total) {
    const ratio = total <= 1 ? 0 : index / (total - 1);
    const hue = 210 - ratio * 120;
    return "hsl(" + hue + ", 72%, 52%)";
  }

  /**
   * 计算柱宽：随条目数自适应。
   * @param {number} count 柱条数
   * @returns {number} 单柱宽（px）
   */
  function computeBarWidth(count) {
    const target = Math.round(1200 / count) + 24;
    return Math.max(CHART.barWidthMin, Math.min(CHART.barWidthMax, target));
  }

  /**
   * 渲染柱状图。
   * @param {Array<{name:string, v:number}>} items 已按指标排序的条目
   * @param {string} metric 指标字段名
   * @param {boolean} ascending 是否升序（仅影响外部排序，此处只消费顺序）
   * @returns {Object} 统计结果 { count, skipped, max, min, sum }
   */
  function renderBars(items, metric) {
    const container = document.getElementById("chart-inner");
    const withValue = items
      .map((item) => ({ name: item.name, value: util.toNumber(item.agg ? item.agg[metric] : null) }))
      .filter((item) => !Number.isNaN(item.value));

    if (withValue.length === 0) {
      container.innerHTML =
        '<div class="empty">该层级没有任何条目包含指标「' + util.escapeHtml(metric) + "」</div>";
      return { count: 0, skipped: items.length, max: null, min: null, sum: null };
    }

    const n = withValue.length;
    const max = Math.max.apply(null, withValue.map((item) => item.value));
    const min = Math.min.apply(null, withValue.map((item) => item.value));
    const sum = withValue.reduce((acc, item) => acc + item.value, 0);

    const barWidth = computeBarWidth(n);
    const plotHeight = CHART.plotHeight;
    const topLabelZone = CHART.topLabelZone;           // 顶部预留区：容纳最高柱的数值标签
    const bottom = CHART.labelHeight + CHART.labelGap; // 名称区占位（标签区 + 柱底间距）
    const columnHeight = topLabelZone + plotHeight + bottom;
    const valueLabelHeight = 12; // 数值标签自身高度（line-height 1）

    const html = withValue.map((item, index) => {
      const height = Math.max(CHART.barMinHeight, (item.value / max) * plotHeight);
      // 柱顶相对列顶部的高度 = topLabelZone + (plotHeight - height)
      // 数值标签紧贴各自柱顶上方：柱顶 Y - valueGap - 标签自身高度
      const numberTop = topLabelZone + (plotHeight - height - CHART.valueGap - valueLabelHeight);
      const width = barWidth + "px";
      const name = util.escapeHtml(item.name);
      const valueText = util.formatValue(item.value, metric);
      const color = barColor(index, n);
      // 逐柱错峰：柱体生长 delay，数值标签在生长完成后淡入
      const growDelay = Math.min(index * 16, 560);
      const growDelayMs = growDelay + "ms";
      const valueDelayMs = (growDelay + 520) + "ms";

      return (
        '<div class="bar-column" style="width:' + width + ';height:' + columnHeight + 'px" ' +
        'data-name="' + name + '" data-value="' + valueText + '">' +
        '<div class="bar-value" style="top:' + numberTop + 'px;animation-delay:' + valueDelayMs + '">' +
        valueText + "</div>" +
        // 柱体（自下而上生长动画）
        '<div class="bar-rect" style="bottom:' + bottom + 'px;height:' +
        height + "px;background:" + color + ";animation-delay:" + growDelayMs + '"></div>' +
        // 名称标签：底部水平（超出省略号，悬停看全名）
        '<div class="bar-name" style="bottom:' + (CHART.labelHeight - 24) + 'px">' +
        name + "</div>" +
        "</div>"
      );
    }).join("");

    container.style.width = (n * barWidth) + "px";
    container.innerHTML = html;
    return { count: n, skipped: items.length - n, max: max, min: min, sum: sum };
  }

  /**
   * 渲染整车级指标卡片总览。
   * @param {Object} project projectCircuitInfo
   */
  function renderProject(project) {
    const grid = document.getElementById("project-grid");
    const html = Object.keys(project).map((key) => {
      const raw = project[key];
      const isNumeric = !Number.isNaN(util.toNumber(raw));
      const text = isNumeric
        ? util.formatValue(raw, key)
        : util.escapeHtml(raw === null || raw === undefined ? "" : String(raw));
      return (
        '<div class="stat-card">' +
        '<div class="stat-name" title="' + util.escapeHtml(key) + '">' +
        util.escapeHtml(key) + "</div>" +
        '<div class="stat-val">' + text + "</div>" +
        "</div>"
      );
    }).join("");
    grid.innerHTML = html;
  }

  /* ---------------- 悬浮提示框 ---------------- */

  let tooltipEl = null;   // 提示框 DOM
  let chartContainer = null; // 柱状图容器 DOM

  /**
   * 初始化悬浮提示框的事件委托（对柱体容器绑定一次，随内层元素重建依然生效）。
   * 鼠标悬停柱体时，瞬间显示该柱的名称与数值，并跟随鼠标移动。
   */
  function initTooltip() {
    tooltipEl = document.getElementById("chart-tooltip");
    chartContainer = document.getElementById("chart-inner");
    if (!tooltipEl || !chartContainer) return;
    chartContainer.addEventListener("mouseover", onColumnEnter);
    // 跟随移动监听挂到 document，保证光标离开柱体子元素后仍能持续定位
    document.addEventListener("mousemove", onColumnMove);
    chartContainer.addEventListener("mouseout", onColumnLeave);
  }

  /**
   * 命中柱体元素；未命中返回 null。
   * @param {Event} event 鼠标事件
   * @returns {HTMLElement|null} 被命中的 .bar-column
   */
  function hitColumn(event) {
    const target = event.target.closest ? event.target.closest(".bar-column") : null;
    return target || null;
  }

  /**
   * 显示提示框内容。
   * @param {HTMLElement} column 柱体列元素
   */
  function showTooltip(column) {
    const rawName = column.dataset.name || "";
    // 名称中的换行（如系统名含 \n）替换为空格，提升可读性
    const name = rawName.replace(/\n/g, " ");
    const value = column.dataset.value || "";
    tooltipEl.innerHTML =
      '<span class="chart-tooltip__name">' + util.escapeHtml(name) + "</span>" +
      '<span class="chart-tooltip__value">' + util.escapeHtml(value) + "</span>";
    tooltipEl.classList.add("is-visible");
    tooltipEl.setAttribute("aria-hidden", "false");
  }

  /**
   * 隐藏提示框。
   */
  function hideTooltip() {
    tooltipEl.classList.remove("is-visible");
    tooltipEl.setAttribute("aria-hidden", "true");
  }

  /**
   * 依据鼠标位置定位提示框（居中于光标正上方，指向箭头朝下）。
   * 超出视口边缘时自动翻转或夹取，保证始终可见。
   * @param {MouseEvent} event 鼠标事件
   */
  function positionTooltip(event) {
    const gap = 16;
    // 提示框水平居中于光标
    const halfWidth = tooltipEl.offsetWidth / 2;
    let left = event.clientX - halfWidth;
    let top = event.clientY - tooltipEl.offsetHeight - gap;

    // 水平方向钳制在视口内
    const minLeft = 8;
    const maxLeft = window.innerWidth - tooltipEl.offsetWidth - 8;
    left = Math.max(minLeft, Math.min(maxLeft, left));

    // 上方放不下则翻到下方，并切换箭头方向
    const flipped = top < 8;
    if (flipped) {
      top = event.clientY + gap;
      tooltipEl.classList.add("is-flipped");
    } else {
      tooltipEl.classList.remove("is-flipped");
    }

    tooltipEl.style.left = Math.round(left) + "px";
    tooltipEl.style.top = Math.round(top) + "px";
  }

  /**
   * 鼠标进入柱体。
   * @param {Event} event 鼠标事件
   */
  function onColumnEnter(event) {
    const column = hitColumn(event);
    if (!column) return;
    showTooltip(column);
    positionTooltip(event);
  }

  /**
   * 鼠标在柱体上移动（提示框跟随）。
   * @param {Event} event 鼠标事件
   */
  function onColumnMove(event) {
    if (!tooltipEl.classList.contains("is-visible")) return;
    positionTooltip(event);
  }

  /**
   * 鼠标离开柱体区域。
   * @param {Event} event 鼠标事件
   */
  function onColumnLeave(event) {
    const next = event.relatedTarget;
    // 仅当真正离开柱体容器时才隐藏（避免在子元素间移动时闪烁）
    if (next && chartContainer.contains(next)) return;
    hideTooltip();
  }

  root.AppChart = {
    renderBars: renderBars,
    renderProject: renderProject,
    initTooltip: initTooltip,
    barColor: barColor,
    computeBarWidth: computeBarWidth
  };
})(typeof window !== "undefined" ? window : globalThis);