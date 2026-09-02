/**
 * 通用工具函数
 * ============================================================
 * 提供数值解析、格式化、HTML 转义等无业务上下文的基础能力。
 * 通过 window.AppUtil 暴露。
 */
(function (root) {
  "use strict";

  /**
   * 将字段值转为数值。
   * 支持 number / "12.34" / "100.00%" 等字符串，兼容千分位分隔符。
   * @param {*} value 待解析值
   * @returns {number} 解析成功的数值；无法解析时返回 NaN
   */
  function toNumber(value) {
    if (value === null || value === undefined) return NaN;
    if (typeof value === "number") return value;
    const cleaned = String(value)
      .replace(/[,，]/g, "")
      .trim()
      .replace(/%$/, "");
    if (cleaned === "") return NaN;
    const num = Number(cleaned);
    return Number.isFinite(num) ? num : NaN;
  }

  /**
   * 判断字段值是否可作数值指标（百分比字符串也算）。
   * @param {*} value 字段值
   * @returns {boolean} 是否数值型
   */
  function isNumericLike(value) {
    return !Number.isNaN(toNumber(value));
  }

  /**
   * 按指标语义格式化数值：整数不带小数；百分比字段追加 "%"。
   * @param {*} value 数值或原始值
   * @param {string} key 指标字段名
   * @returns {string} 格式化后的展示文本
   */
  function formatValue(value, key) {
    const num = toNumber(value);
    if (Number.isNaN(num)) {
      const fallback = value === null || value === undefined ? "" : value;
      return String(fallback);
    }
    const isPercent = String(key).includes("百分比");
    const digits = Number.isInteger(num) ? 0 : (Math.abs(num) < 10 ? 2 : 1);
    let text = num.toLocaleString("zh-CN", {
      maximumFractionDigits: digits,
      minimumFractionDigits: 0
    });
    return isPercent ? text + "%" : text;
  }

  /**
   * HTML 转义，防止 XSS。
   * @param {*} value 任意值
   * @returns {string} 转义后的 HTML 安全文本
   */
  function escapeHtml(value) {
    const map = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
    return String(value).replace(/[&<>"']/g, (char) => map[char]);
  }

  /**
   * 从文本中提取第一个完整 JSON 对象（括号配对扫描，兼容字符串转义）。
   * 用于解析「拓扑优化json日志.txt」此类 JSON 日志文件（首份文档后可能追加残缺片段）。
   * @param {string} text 待解析文本
   * @returns {string|null} 提取到的首个完整 JSON 串；失败返回 null
   */
  function extractFirstJson(text) {
    const start = text.indexOf("{");
    if (start < 0) return null;
    let depth = 0;
    let inString = false;
    let escaped = false;
    for (let i = start; i < text.length; i++) {
      const char = text[i];
      if (inString) {
        if (escaped) escaped = false;
        else if (char === "\\") escaped = true;
        else if (char === "\"") inString = false;
        continue;
      }
      if (char === "\"") {
        inString = true;
        continue;
      }
      if (char === "{") depth++;
      else if (char === "}") {
        depth--;
        if (depth === 0) return text.slice(start, i + 1);
      }
    }
    return null;
  }

  root.AppUtil = {
    toNumber: toNumber,
    isNumericLike: isNumericLike,
    formatValue: formatValue,
    escapeHtml: escapeHtml,
    extractFirstJson: extractFirstJson
  };
})(typeof window !== "undefined" ? window : globalThis);