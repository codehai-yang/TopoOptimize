/**
 * Excel 解析层
 * ============================================================
 * 负责把生产环境导出的 Excel（工具计算结果demo…）解析成与 output.json 相同
 * 顶层结构的数据，供 AppData / AppChart 复用渲染。
 *
 * 顶层结构：
 *   {
 *     systemCircuitInfo:          { 系统名: { circuitInfoIntergation, circuitList } },
 *     elecRelatedCircuitInfo:     { 用电器名: { circuitInfoIntergation, circuitList } },
 *     elecInterfaceRelatedCircuitInfo: { 用电器: { 接口: { circuitInfoIntergation, circuitList } } },
 *     bundeleRelatedCircuitInfo:  { 分支名称: { circuitInfoIntergation, circuitList } },
 *     circuitInfo:                [ 回路对象 ],
 *     projectCircuitInfo:         { 指标... }
 *   }
 *
 * 要点：
 *  - 各表数据行第 1 列为方案名称；汇总行以「回路编号」列的固定文案（如
 *    “系统内所有回路”）识别，作为该级的 circuitInfoIntergation 指标。
 *  - 生产模板无独立「回路id」列，故统一用「回路编号」作为回路唯一键，
 *    circuitList 元素与 circuitInfo[].回路id 均取回路编号（统一转字符串），
 *    与 data.js 中 buildCircuitMap 的按 id 反查逻辑对齐。
 *  - 单元格用 raw:false 取展示值（百分比为 "50.68%" 等字符串），复用
 *    util.toNumber / formatValue 解析，无需类型转换。
 * 通过 window.AppExcel 暴露。
 */
(function (root) {
  "use strict";

  var hasXLSX = typeof XLSX !== "undefined" && !!XLSX;

  /* ---------------- 工具函数 ---------------- */

  /** 取单元格字符串（null/undefined -> ""） */
  function str(v) {
    return v === null || v === undefined ? "" : String(v);
  }

  /** 定位含关键字的第一个工作表名 */
  function findSheet(wb, keyword) {
    var names = wb.SheetNames || [];
    for (var i = 0; i < names.length; i++) {
      if (names[i].indexOf(keyword) >= 0) return names[i];
    }
    return null;
  }

  /** 读取工作表为二维数组（展示值，raw:false） */
  function rowsOf(ws) {
    if (!hasXLSX) throw new Error("缺少 XLSX 解析库，请检查是否已加载 lib/xlsx.full.min.js");
    return XLSX.utils.sheet_to_json(ws, { header: 1, raw: false, defval: null });
  }

  /** 定位数据首行下标：表头行第 1 列为「方案名称」，其后第 1 列非空的为数据起行 */
  function dataStartIndex(rows) {
    for (var i = 0; i < rows.length; i++) {
      if (str(rows[i][0]).trim() === "方案名称") {
        for (var j = i + 1; j < rows.length; j++) {
          if (str(rows[j][0]).trim() !== "") return j;
        }
        return rows.length; // 无数据
      }
    }
    return 0;
  }

  /** 按下标映射采集一个对象 */
  function pick(rows, idx, map) {
    var row = rows[idx];
    var obj = {};
    if (!row) return obj;
    Object.keys(map).forEach(function (col) {
      var v = row[col];
      if (v === null || v === undefined) return;
      obj[map[col]] = v;
    });
    return obj;
  }

  /* ---------------- 各表列映射（下标 -> output 键名） ---------------- */

  /** 整车级：projectCircuitInfo */
  var PROJECT_MAP = {
    1: "回路总重量", 2: "回路总长度", 3: "总成本",
    4: "回路导线总成本", 5: "端子总成本", 6: "连接器塑壳总成本", 7: "防水塞总成本",
    8: "回路数量-A类(根)", 9: "回路数量-B类(根)",
    10: "回路重量均值(克/根)", 11: "回路成本均值(元/根)", 12: "回路长度均值(米/根)",
    13: "回路打断总次数(根)", 14: "回路打断数量占比(百分比)", 15: "回路打断成本总值(元)", 16: "回路打断成本均值(元/根)",
    17: "回器绕线总数量(根)", 18: "回路绕线数量占比(百分比)", 19: "回路绕线长度总值(米)", 20: "回路绕线长度均值(米/根)",
    21: "能量流绕路总数量(根)", 22: "能量流绕路数量占比(百分比)", 23: "能量流绕路长度总值(米)", 24: "能量流绕路长度均值(米/根)"
  };

  /** 系统级：汇总指标（回路编号列在 col2） */
  var SYS_MAP = {
    8: "回路理论直径", 9: "回路总长度", 10: "回路总重量", 11: "总成本",
    12: "回路导线总成本", 13: "端子总成本", 14: "连接器塑壳总成本", 15: "防水塞总成本",
    16: "回路数量-A类(根)", 17: "回路数量-B类(根)",
    18: "回路重量均值(克/根)", 19: "回路成本均值(元/根)", 20: "回路长度均值(米/根)",
    21: "回路打断总次数(根)", 22: "回路打断数量占比(百分比)", 23: "回路打断成本总值(元)", 24: "回路打断成本均值(元/根)",
    25: "回器绕线总数量(根)", 26: "回路绕线数量占比(百分比)", 27: "回路绕线长度总值(米)", 28: "回路绕线长度均值(米/根)",
    29: "能量流绕路总数量(根)", 30: "能量流绕路数量占比(百分比)", 31: "能量流绕路长度总值(米)", 32: "能量流绕路长度均值(米/根)"
  };

  /** 用电器级 / 接口级：汇总指标（多了一列「相关用电器接口编号」，故整体右移 1） */
  var ELEC_MAP = {
    9: "回路理论直径", 10: "回路总长度", 11: "回路总重量", 12: "总成本",
    13: "回路导线总成本", 14: "端子总成本", 15: "连接器塑壳总成本", 16: "防水塞总成本",
    17: "回路数量-A类(根)", 18: "回路数量-B类(根)",
    19: "回路重量均值(克/根)", 20: "回路成本均值(元/根)", 21: "回路长度均值(米/根)",
    22: "回路打断总次数(根)", 23: "回路打断数量占比(百分比)", 24: "回路打断成本总值(元)", 25: "回路打断成本均值(元/根)",
    26: "回器绕线总数量(根)", 27: "回路绕线数量占比(百分比)", 28: "回路绕线长度总值(米)", 29: "回路绕线长度均值(米/根)",
    30: "能量流绕路总数量(根)", 31: "能量流绕路数量占比(百分比)", 32: "能量流绕路长度总值(米)", 33: "能量流绕路长度均值(米/根)"
  };

  /** 分支级：汇总指标（仅基础成本分解） */
  var BRANCH_MAP = {
    8: "回路理论直径", 9: "回路总长度", 10: "回路总重量", 11: "总成本",
    12: "回路导线总成本", 13: "端子总成本", 14: "连接器塑壳总成本", 15: "防水塞总成本"
  };

  /** 每一条回路信息：单回路字段 */
  var CIRCUIT_MAP = {
    1: "所属系统", 2: "回路编号", 3: "回路信号名", 4: "导线选型", 5: "回路属性",
    6: "起点用电器名称", 7: "起点位置名称", 8: "起点用电器接口编号",
    9: "终点用电器名称", 10: "终点位置名称", 11: "终点用电器接口编号",
    12: "回路理论直径", 13: "回路长度", 14: "回路重量", 15: "回路总成本",
    16: "回路导线成本", 17: "端子成本", 18: "连接器塑壳成本", 19: "防水塞成本"
  };

  /** 系统级明细：单回路字段（补齐「每一条回路信息」未覆盖的回路，保证 circuitList 可反查） */
  var SYS_DETAIL_MAP = {
    1: "所属系统", 3: "起点用电器名称", 4: "终点用电器名称", 5: "回路信号名",
    6: "导线选型", 7: "回路属性", 8: "回路理论直径", 9: "回路长度", 10: "回路重量",
    11: "回路总成本", 12: "回路导线成本", 13: "端子成本", 14: "连接器塑壳成本", 15: "防水塞成本"
  };

  /** 用电器级 / 接口级明细：单回路字段 */
  var ELEC_DETAIL_MAP = {
    4: "起点用电器名称", 5: "终点用电器名称", 6: "回路信号名", 7: "导线选型", 8: "回路属性",
    9: "回路理论直径", 10: "回路长度", 11: "回路重量", 12: "回路总成本",
    13: "回路导线成本", 14: "端子成本", 15: "连接器塑壳成本", 16: "防水塞成本"
  };

  /** 分支级明细：单回路字段 */
  var BRANCH_DETAIL_MAP = {
    3: "起点用电器名称", 4: "终点用电器名称", 5: "回路信号名", 6: "导线选型", 7: "回路属性",
    8: "回路理论直径", 9: "回路长度", 10: "回路重量", 11: "回路总成本",
    12: "回路导线成本", 13: "端子成本", 14: "连接器塑壳成本", 15: "防水塞成本"
  };

  /* ---------------- 各层级解析 ---------------- */

  function parseProject(ws) {
    if (!ws) return {};
    var rows = rowsOf(ws);
    var start = dataStartIndex(rows);
    if (start >= rows.length) return {};
    return pick(rows, start, PROJECT_MAP);
  }

  /**
   * 解析单回路明细：circuitInfo 数组 + 回路编号索引。
   * @returns {{list:Array, map:Object}} list 为回路对象数组，map 为 编号->对象
   */
  function parseCircuit(ws) {
    var rows = rowsOf(ws);
    var start = dataStartIndex(rows);
    var list = [];
    var map = {};
    var fallback = 1;
    for (var i = start; i < rows.length; i++) {
      var row = rows[i];
      if (!row || str(row[0]).trim() === "") continue; // 跳过空行
      var obj = pick(rows, i, CIRCUIT_MAP);
      var num = obj["回路编号"];
      var key = (num === null || num === undefined || str(num).trim() === "") ? "" : str(num);
      if (key === "") { key = "C" + fallback; fallback++; }
      obj["回路id"] = key;
      list.push(obj);
      map[key] = obj;
    }
    return { list: list, map: map };
  }

  /**
   * 通用「分组汇总 + 明细回路编号」解析。
   * @param {Array} rows 工作表二维数组
   * @param {number} dataStart 数据首行下标
   * @param {Object} cfg { summaryIdx, summaryText, nameIdx, nameIdx2, listIdx, aggMap, detailMap, collector }
   *   collector 可选：{ hasKey(num):boolean, add(obj):void }，用于把明细行回路口并入全局回路表。
   * @returns {Array<{name:string,name2:string,agg:Object,list:Array}>}
   */
  function groupRows(rows, dataStart, cfg) {
    var groups = [];
    var cur = null;
    for (var i = dataStart; i < rows.length; i++) {
      var row = rows[i];
      if (!row) continue;
      var summaryVal = str(row[cfg.summaryIdx]).trim();
      if (summaryVal === cfg.summaryText) {
        cur = {
          name: str(row[cfg.nameIdx]).trim(),
          name2: cfg.nameIdx2 != null ? str(row[cfg.nameIdx2]).trim() : "",
          agg: {},
          list: []
        };
        Object.keys(cfg.aggMap).forEach(function (col) {
          var v = row[col];
          if (v === null || v === undefined) return;
          cur.agg[cfg.aggMap[col]] = v;
        });
        groups.push(cur);
      } else if (cur && str(row[0]).trim() !== "") {
        // 明细行：第 1 列（方案名称）非空即视为数据行，归入当前分组并收集回路编号
        var num = row[cfg.listIdx];
        var numStr = (num === null || num === undefined) ? "" : str(num);
        if (numStr.trim() !== "") {
          cur.list.push(numStr);
          // 该回路口未纳入全局回路表时，用当前表的明细字段补齐一条，保证 circuitList 可反查
          if (cfg.collector && cfg.detailMap && !cfg.collector.hasKey(numStr)) {
            var obj = pick(rows, i, cfg.detailMap);
            obj["回路id"] = numStr;
            if (obj["回路编号"] === undefined) obj["回路编号"] = numStr;
            cfg.collector.add(obj);
          }
        }
      }
      // 汇总行与明细行均满足“第 1 列非空”，故以 summaryIdx 文案判别汇总，明细行再收编号。
    }
    return groups;
  }

  function buildHierarchy(wb) {
    if (!wb || !wb.SheetNames || wb.SheetNames.length === 0) {
      throw new Error("未能解析出任何工作表，请确认上传的是有效的 Excel 文件");
    }

    var out = {};

    // 1. 回路明细（必须）。以「每一条回路信息」为基底，并在解析各分组明细时把
    //    未覆盖的回路也并入这份全局回路表，保证各层 circuitList 均可按回路编号反查。
    var circuitWs = findSheet(wb, "每一条");
    if (!circuitWs) throw new Error("工作簿中缺少「每一条回路信息」工作表，无法解析回路数据");
    var circuit = parseCircuit(wb.Sheets[circuitWs]);
    out.circuitInfo = circuit.list;
    var collector = {
      _map: circuit.map,
      hasKey: function (key) { return !!circuit.map[key]; },
      add: function (obj) {
        circuit.map[obj["回路id"]] = obj;
        out.circuitInfo.push(obj);
      }
    };

    // 2. 整车级（可选）
    var projWs = findSheet(wb, "整车");
    out.projectCircuitInfo = projWs ? parseProject(wb.Sheets[projWs]) : {};

    // 3. 系统级
    var sysWs = findSheet(wb, "系统级");
    out.systemCircuitInfo = {};
    if (sysWs) {
      var sysRows = rowsOf(wb.Sheets[sysWs]);
      var sysStart = dataStartIndex(sysRows);
      groupRows(sysRows, sysStart, {
        summaryIdx: 2, summaryText: "系统内所有回路", nameIdx: 1,
        nameIdx2: null, listIdx: 2, aggMap: SYS_MAP,
        detailMap: SYS_DETAIL_MAP, collector: collector
      }).forEach(function (g) {
        if (g.name) out.systemCircuitInfo[g.name] = { circuitInfoIntergation: g.agg, circuitList: g.list };
      });
    }

    // 4. 用电器级
    var elecWs = findSheet(wb, "用电器");
    out.elecRelatedCircuitInfo = {};
    if (elecWs) {
      var elecRows = rowsOf(wb.Sheets[elecWs]);
      var elecStart = dataStartIndex(elecRows);
      groupRows(elecRows, elecStart, {
        summaryIdx: 3, summaryText: "用电器相关所有回路", nameIdx: 1,
        nameIdx2: null, listIdx: 3, aggMap: ELEC_MAP,
        detailMap: ELEC_DETAIL_MAP, collector: collector
      }).forEach(function (g) {
        if (g.name) out.elecRelatedCircuitInfo[g.name] = { circuitInfoIntergation: g.agg, circuitList: g.list };
      });
    }

    // 5. 接口级（嵌套：用电器 -> 接口类型）
    var ifaceWs = findSheet(wb, "接口");
    out.elecInterfaceRelatedCircuitInfo = {};
    if (ifaceWs) {
      var ifaceRows = rowsOf(wb.Sheets[ifaceWs]);
      var ifaceStart = dataStartIndex(ifaceRows);
      groupRows(ifaceRows, ifaceStart, {
        summaryIdx: 3, summaryText: "接口相关所有回路", nameIdx: 1,
        nameIdx2: 2, listIdx: 3, aggMap: ELEC_MAP,
        detailMap: ELEC_DETAIL_MAP, collector: collector
      }).forEach(function (g) {
        if (!g.name || !g.name2) return;
        if (!out.elecInterfaceRelatedCircuitInfo[g.name]) out.elecInterfaceRelatedCircuitInfo[g.name] = {};
        out.elecInterfaceRelatedCircuitInfo[g.name][g.name2] = {
          circuitInfoIntergation: g.agg, circuitList: g.list
        };
      });
    }

    // 6. 分支级
    var branchWs = findSheet(wb, "分支");
    out.bundeleRelatedCircuitInfo = {};
    if (branchWs) {
      var brRows = rowsOf(wb.Sheets[branchWs]);
      var brStart = dataStartIndex(brRows);
      groupRows(brRows, brStart, {
        summaryIdx: 2, summaryText: "分支相关所有回路", nameIdx: 1,
        nameIdx2: null, listIdx: 2, aggMap: BRANCH_MAP,
        detailMap: BRANCH_DETAIL_MAP, collector: collector
      }).forEach(function (g) {
        if (g.name) out.bundeleRelatedCircuitInfo[g.name] = { circuitInfoIntergation: g.agg, circuitList: g.list };
      });
    }

    return out;
  }

  root.AppExcel = { buildHierarchy: buildHierarchy };
})(typeof window !== "undefined" ? window : globalThis);