const { pres, C, makeShadow, addFooter, addTitleBar, addCard, sectionDivider } = require("./lib");

// ===== 第1页：封面 =====
{
  const s = pres.addSlide();
  s.background = { color: C.navy };
  s.addShape(pres.shapes.OVAL, { x: 9.5, y: -2, w: 6, h: 6, fill: { color: C.accentDark, transparency: 70 } });
  s.addShape(pres.shapes.OVAL, { x: 10.5, y: 3.5, w: 4, h: 4, fill: { color: C.gold, transparency: 80 } });
  s.addShape(pres.shapes.RECTANGLE, { x: 0.8, y: 2.7, w: 0.12, h: 2.2, fill: { color: C.gold } });
  s.addText("整车线束智能优化", { x: 1.2, y: 2.5, w: 10, h: 1.0, fontSize: 44, bold: true, color: C.white, fontFace: "Calibri", margin: 0 });
  s.addText("算法平台技术汇报", { x: 1.2, y: 3.5, w: 10, h: 0.7, fontSize: 28, color: C.iceBlue, fontFace: "Calibri", margin: 0 });
  s.addText("以算法驱动降本增效，打造整车电气设计智能化核心竞争力", { x: 1.2, y: 4.4, w: 10, h: 0.5, fontSize: 14, color: C.iceBlue, fontFace: "Calibri", italic: true, margin: 0 });
  s.addShape(pres.shapes.LINE, { x: 1.2, y: 5.2, w: 4, h: 0, line: { color: C.gold, width: 2 } });
  s.addText("汇报对象：管理层", { x: 1.2, y: 5.4, w: 5, h: 0.35, fontSize: 12, color: C.white, fontFace: "Calibri", margin: 0 });
  s.addText("汇报日期：2026年8月", { x: 1.2, y: 5.75, w: 5, h: 0.35, fontSize: 12, color: C.white, fontFace: "Calibri", margin: 0 });
}

// ===== 第2页：目录 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "汇报目录", "CONTENTS");
  const items = [
    { num: "01", title: "产品全景与价值定位", desc: "平台整体架构与核心业务价值" },
    { num: "02", title: "整车回路计算引擎", desc: "ProjectCircuitInfoOutput — 一切优化的数据底座" },
    { num: "03", title: "线束拓扑优化算法", desc: "HarnessBranchTopoOptimize — 分支通断智能寻优" },
    { num: "04", title: "控制器位置优化算法", desc: "ElecPositionVariantCalculation — 用电器位置智能推荐" },
    { num: "05", title: "配电驱动优化算法", desc: "PowerDistributionDriveOptimization — 供电路径全局寻优" },
    { num: "06", title: "质量检查体系", desc: "ErrorOutput 检查项矩阵 — 优化前的质量门禁" },
    { num: "07", title: "产品价值总结与展望", desc: "降本数据、技术壁垒与演进路线" },
  ];
  const startY = 1.5, rowH = 0.72;
  items.forEach((it, i) => {
    const y = startY + i * rowH;
    addCard(s, 0.8, y, 11.7, rowH - 0.1, { accentColor: C.accent });
    s.addText(it.num, { x: 1.1, y, w: 0.9, h: rowH - 0.1, fontSize: 24, bold: true, color: C.navy, fontFace: "Calibri", valign: "middle", margin: 0 });
    s.addText(it.title, { x: 2.2, y, w: 4.5, h: rowH - 0.1, fontSize: 15, bold: true, color: C.darkGray, fontFace: "Calibri", valign: "middle", margin: 0 });
    s.addText(it.desc, { x: 6.8, y, w: 5.5, h: rowH - 0.1, fontSize: 12, color: C.gray, fontFace: "Calibri", valign: "middle", margin: 0 });
  });
  addFooter(s, 2);
}

// ===== 第3页：产品全景 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "产品全景与价值定位", "一个平台 · 四大算法引擎 · 全链路质量门禁");
  addCard(s, 0.5, 1.5, 5.8, 5.2, { accentColor: C.navy });
  s.addText("平台定位", { x: 0.8, y: 1.7, w: 5.2, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  s.addText("整车线束智能优化算法平台", { x: 0.8, y: 2.2, w: 5.2, h: 0.4, fontSize: 13, color: C.darkGray, fontFace: "Calibri", bold: true, margin: 0 });
  s.addText([
    { text: "面向整车电气架构设计场景，将人工经验驱动的线束设计，升级为", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
    { text: "数据 + 算法驱动的智能寻优", options: { fontSize: 12, color: C.accent, bold: true } },
    { text: "，在拓扑结构、控制器位置、配电驱动三个维度实现全局成本最优。", options: { breakLine: true, fontSize: 12, color: C.darkGray } },
  ], { x: 0.8, y: 2.7, w: 5.2, h: 1.2, fontFace: "Calibri", margin: 0 });
  const metrics = [
    { val: "成本", unit: "↓", desc: "导线/接插件/密封 全量计入" },
    { val: "重量", unit: "↓", desc: "回路总重量同步优化" },
    { val: "长度", unit: "↓", desc: "回路走线最短化" },
  ];
  metrics.forEach((m, i) => {
    const mx = 0.8 + i * 1.8;
    s.addShape(pres.shapes.RECTANGLE, { x: mx, y: 4.1, w: 1.6, h: 1.0, fill: { color: C.navy }, shadow: makeShadow() });
    s.addText(m.val, { x: mx, y: 4.15, w: 1.6, h: 0.5, fontSize: 16, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addText(m.unit + " " + m.desc, { x: mx, y: 4.6, w: 1.6, h: 0.45, fontSize: 9, color: C.iceBlue, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
  });
  s.addText("三大优化目标 · 成本权重 98% · 重量/长度各 1%", { x: 0.8, y: 5.3, w: 5.2, h: 0.4, fontSize: 11, color: C.gray, fontFace: "Calibri", italic: true, margin: 0 });
  s.addText("支持多线程并行计算 · 遗传算法全局寻优 · Top20 方案推荐", { x: 0.8, y: 5.7, w: 5.2, h: 0.4, fontSize: 11, color: C.gray, fontFace: "Calibri", italic: true, margin: 0 });
  s.addText("支持优化过程可中断 · 可恢复 · 可追溯", { x: 0.8, y: 6.1, w: 5.2, h: 0.4, fontSize: 11, color: C.gray, fontFace: "Calibri", italic: true, margin: 0 });

  addCard(s, 6.6, 1.5, 6.2, 5.2, { accentColor: C.gold });
  s.addText("四大算法引擎", { x: 6.9, y: 1.7, w: 5.6, h: 0.5, fontSize: 18, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const engines = [
    { name: "整车回路计算引擎", desc: "构建拓扑图 · 计算回路成本/重量/长度", color: C.accent },
    { name: "线束拓扑优化", desc: "分支通断 B/C/S 遗传寻优", color: C.green },
    { name: "控制器位置优化", desc: "用电器可变位置智能推荐", color: C.gold },
    { name: "配电驱动优化", desc: "供电/驱动回路连接关系寻优", color: C.red },
  ];
  engines.forEach((e, i) => {
    const ey = 2.3 + i * 1.05;
    s.addShape(pres.shapes.RECTANGLE, { x: 6.9, y: ey, w: 0.08, h: 0.85, fill: { color: e.color } });
    s.addText(e.name, { x: 7.15, y: ey, w: 5.3, h: 0.4, fontSize: 14, bold: true, color: C.darkGray, fontFace: "Calibri", valign: "middle", margin: 0 });
    s.addText(e.desc, { x: 7.15, y: ey + 0.4, w: 5.3, h: 0.4, fontSize: 11, color: C.gray, fontFace: "Calibri", valign: "middle", margin: 0 });
  });
  addFooter(s, 3);
}

// ===== 第4页：产品架构总览 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "产品架构总览", "从输入到输出 · 一条完整的数据流水线");
  const flow = [
    { title: "整车数据输入", desc: "拓扑/回路/用电器\n导线库/商务价", color: C.gray },
    { title: "质量检查门禁", desc: "ErrorOutput\n8类检查接口", color: C.red },
    { title: "回路计算引擎", desc: "ProjectCircuit\nInfoOutput", color: C.accent },
    { title: "三大优化算法", desc: "拓扑/位置/\n配电驱动", color: C.green },
    { title: "Top方案输出", desc: "成本/重量/长度\n对比与推荐", color: C.gold },
  ];
  const startX = 0.5, blockW = 2.2, gap = 0.25, blockY = 2.2, blockH = 1.8;
  flow.forEach((f, i) => {
    const x = startX + i * (blockW + gap);
    s.addShape(pres.shapes.RECTANGLE, { x, y: blockY, w: blockW, h: blockH, fill: { color: C.white }, shadow: makeShadow(), line: { color: C.lightGray, width: 0.5 } });
    s.addShape(pres.shapes.RECTANGLE, { x, y: blockY, w: blockW, h: 0.5, fill: { color: f.color } });
    s.addText(f.title, { x, y: blockY, w: blockW, h: 0.5, fontSize: 13, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addText(f.desc, { x, y: blockY + 0.6, w: blockW, h: 1.1, fontSize: 11, color: C.darkGray, fontFace: "Calibri", align: "center", valign: "top", margin: 0 });
    if (i < flow.length - 1) {
      s.addShape(pres.shapes.RECTANGLE, { x: x + blockW + 0.02, y: blockY + blockH / 2 - 0.04, w: gap - 0.04, h: 0.08, fill: { color: C.navy } });
    }
  });
  addCard(s, 0.5, 4.5, 12.3, 2.0, { accentColor: C.navy });
  s.addText("核心设计理念", { x: 0.8, y: 4.65, w: 5, h: 0.4, fontSize: 15, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const concepts = [
    { t: "计算与优化解耦", d: "回路计算引擎作为独立底座，所有优化算法复用同一套成本计算逻辑，保证结果一致性" },
    { t: "检查前置", d: "优化前强制执行质量检查，拦截缺失/矛盾数据，避免无效计算消耗算力" },
    { t: "多目标加权", d: "成本 98% + 重量 1% + 长度 1%，以商务成本为核心，兼顾工程指标" },
    { t: "可中断可恢复", d: "OptimizeStopStatusStore 单例管理优化状态，支持随时中断与结果回溯" },
  ];
  concepts.forEach((c, i) => {
    const cx = 0.8 + (i % 2) * 6.0, cy = 5.1 + Math.floor(i / 2) * 0.7;
    s.addText("● " + c.t, { x: cx, y: cy, w: 5.8, h: 0.3, fontSize: 11, bold: true, color: C.accent, fontFace: "Calibri", margin: 0 });
    s.addText(c.d, { x: cx + 0.25, y: cy + 0.3, w: 5.5, h: 0.35, fontSize: 10, color: C.gray, fontFace: "Calibri", margin: 0 });
  });
  addFooter(s, 4);
}

module.exports = {};
