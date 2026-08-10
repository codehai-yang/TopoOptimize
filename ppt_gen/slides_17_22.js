const { pres, C, makeShadow, addFooter, addTitleBar, addCard, sectionDivider } = require("./lib");

// ===== 第17页：章节分隔 - 质量检查体系 =====
sectionDivider("05", "质量检查体系", "ErrorOutput 检查项矩阵 — 优化前的质量门禁", C.red);

// ===== 第18页：质量检查体系 - 产品价值 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "质量检查体系", "8大检查接口 · 覆盖整车设计全链路 · 优化前强制门禁");
  addCard(s, 0.5, 1.5, 12.3, 1.5, { accentColor: C.navy });
  s.addText("为什么需要质量检查", { x: 0.8, y: 1.65, w: 5, h: 0.4, fontSize: 15, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  s.addText([
    { text: "优化算法依赖完整的整车数据。若输入数据存在缺失、矛盾、格式错误，", options: { breakLine: true, fontSize: 11.5, color: C.darkGray } },
    { text: "轻则计算结果失真，重则算法崩溃、算力浪费。", options: { breakLine: true, fontSize: 11.5, color: C.darkGray } },
    { text: "质量检查体系在优化前强制执行，", options: { fontSize: 11.5, color: C.darkGray } },
    { text: "拦截问题数据，保障优化结果可信。", options: { fontSize: 11.5, color: C.red, bold: true } },
  ], { x: 0.8, y: 2.1, w: 12, h: 0.8, fontFace: "Calibri", margin: 0 });

  // 8大检查接口
  const checks = [
    { name: "BunLenErrorOuput", desc: "分支长度/关键尺寸/项目基本信息缺失", page: "第2页", color: C.gray },
    { name: "TopoErrorOutput", desc: "端点/分支重复、接口直连编号、连通性", page: "第1页", color: C.accent },
    { name: "CircuitErrorOutput", desc: "回路用电器/导线选型/信号名/属性缺失", page: "第3页", color: C.green },
    { name: "ElecLocationErrorOutput", desc: "用电器位置缺失/不在分支端点上", page: "第4页", color: C.gold },
    { name: "HarnessBranchTopoOptiErrorOutPut", desc: "分支通断变种/组团/互斥/闭环约束", page: "拓扑优化前", color: C.red },
    { name: "ElecPositionVariantOutput", desc: "用电器位置变种点设置合法性", page: "位置优化前", color: C.accentDark },
    { name: "PowerTopoOptimizeErrorOutput", desc: "用电器变种点不在分支/未选择/数量过多", page: "配电优化前", color: C.green },
    { name: "PowerDistributionDriveErrorOuput", desc: "回路类型/导线选型/组团互斥矛盾", page: "配电优化前", color: C.gold },
  ];
  const startY = 3.3, colW = 6.0, rowH = 0.85, gap = 0.15;
  checks.forEach((c, i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const x = 0.5 + col * (colW + gap);
    const y = startY + row * (rowH + 0.1);
    addCard(s, x, y, colW, rowH, { accentColor: c.color });
    s.addText(c.name, { x: x + 0.2, y: y + 0.05, w: colW - 1.5, h: 0.3, fontSize: 11, bold: true, color: C.darkGray, fontFace: "Consolas", margin: 0 });
    s.addText(c.desc, { x: x + 0.2, y: y + 0.35, w: colW - 1.5, h: 0.4, fontSize: 10, color: C.gray, fontFace: "Calibri", margin: 0 });
    s.addShape(pres.shapes.RECTANGLE, { x: x + colW - 1.3, y: y + 0.2, w: 1.1, h: 0.4, fill: { color: c.color } });
    s.addText(c.page, { x: x + colW - 1.3, y: y + 0.2, w: 1.1, h: 0.4, fontSize: 9, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
  });
  addFooter(s, 18);
}

// ===== 第19页：质量检查 - 检查项详情 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "质量检查 · 检查项详情", "Error 级别阻断优化 · Warning 级别提示用户");
  const tableData = [
    [
      { text: "检查接口", options: { fill: { color: C.navy }, color: C.white, bold: true, fontSize: 11, fontFace: "Calibri", align: "center", valign: "middle" } },
      { text: "Error 检查项（阻断优化）", options: { fill: { color: C.navy }, color: C.white, bold: true, fontSize: 11, fontFace: "Calibri", align: "center", valign: "middle" } },
      { text: "Warning 检查项（提示）", options: { fill: { color: C.navy }, color: C.white, bold: true, fontSize: 11, fontFace: "Calibri", align: "center", valign: "middle" } },
    ],
    [
      { text: "BunLenError", options: { fill: { color: C.lightBg }, bold: true, fontSize: 10, fontFace: "Calibri", valign: "middle" } },
      { text: "分支长度缺失 · 关键尺寸缺失 · 项目基本信息缺失", options: { fontSize: 9.5, color: C.darkGray, fontFace: "Calibri", valign: "middle" } },
      { text: "—", options: { fontSize: 9.5, color: C.gray, fontFace: "Calibri", align: "center", valign: "middle" } },
    ],
    [
      { text: "TopoError", options: { fill: { color: C.lightBg }, bold: true, fontSize: 10, fontFace: "Calibri", valign: "middle" } },
      { text: "端点重复 · 分支重复 · 起终点缺失 · 接口编号错误 · 分支不连通", options: { fontSize: 9.5, color: C.darkGray, fontFace: "Calibri", valign: "middle" } },
      { text: "—", options: { fontSize: 9.5, color: C.gray, fontFace: "Calibri", align: "center", valign: "middle" } },
    ],
    [
      { text: "CircuitError", options: { fill: { color: C.lightBg }, bold: true, fontSize: 10, fontFace: "Calibri", valign: "middle" } },
      { text: "起点/终点用电器缺失 · 导线选型缺失/不存在 · 属性选择错误", options: { fontSize: 9.5, color: C.darkGray, fontFace: "Calibri", valign: "middle" } },
      { text: "信号名缺失 · 属性缺失 · 所属系统缺失 · 接口编号缺失", options: { fontSize: 9.5, color: C.darkGray, fontFace: "Calibri", valign: "middle" } },
    ],
    [
      { text: "ElecLocation", options: { fill: { color: C.lightBg }, bold: true, fontSize: 10, fontFace: "Calibri", valign: "middle" } },
      { text: "用电器位置缺失 · 用电器不在分支端点上", options: { fontSize: 9.5, color: C.darkGray, fontFace: "Calibri", valign: "middle" } },
      { text: "—", options: { fontSize: 9.5, color: C.gray, fontFace: "Calibri", align: "center", valign: "middle" } },
    ],
    [
      { text: "TopoOptiError", options: { fill: { color: C.lightBg }, bold: true, fontSize: 10, fontFace: "Calibri", valign: "middle" } },
      { text: "分支未选通断变种 · 整车不连通 · 组团未同时选BC · 互斥矛盾 · 闭环分支只选S", options: { fontSize: 9.5, color: C.darkGray, fontFace: "Calibri", valign: "middle" } },
      { text: "—", options: { fontSize: 9.5, color: C.gray, fontFace: "Calibri", align: "center", valign: "middle" } },
    ],
    [
      { text: "PowerDriveError", options: { fill: { color: C.lightBg }, bold: true, fontSize: 10, fontFace: "Calibri", valign: "middle" } },
      { text: "回路类型缺失 · 导线选型缺失 · 起终点缺失 · 组团互斥矛盾 · 回路类型不匹配", options: { fontSize: 9.5, color: C.darkGray, fontFace: "Calibri", valign: "middle" } },
      { text: "所属系统缺失 · 信号名缺失", options: { fontSize: 9.5, color: C.darkGray, fontFace: "Calibri", valign: "middle" } },
    ],
  ];
  s.addTable(tableData, {
    x: 0.5, y: 1.4, w: 12.3,
    colW: [2.0, 6.8, 3.5],
    rowH: 0.75,
    border: { type: "solid", pt: 0.5, color: C.lightGray },
    fontFace: "Calibri",
  });
  addCard(s, 0.5, 6.5, 12.3, 0.5, { accentColor: C.red });
  s.addText("检查结果以 JSON 输出，前端按 Error/Warning 分级展示，Error 项阻断优化流程", { x: 0.8, y: 6.55, w: 12, h: 0.4, fontSize: 11, bold: true, color: C.navy, fontFace: "Calibri", valign: "middle", margin: 0 });
  addFooter(s, 19);
}

// ===== 第20页：章节分隔 - 产品价值总结 =====
sectionDivider("06", "产品价值总结与展望", "降本数据 · 技术壁垒 · 演进路线", C.gold);

// ===== 第21页：产品价值总结 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "产品价值总结", "四大算法引擎 · 三大优化目标 · 全链路质量保障");

  // 上半部分：核心价值
  const vals = [
    { num: "4", label: "算法引擎", desc: "回路计算 + 拓扑/位置/配电优化", color: C.accent },
    { num: "20", label: "Top方案推荐", desc: "保留人工决策空间", color: C.green },
    { num: "8", label: "质量检查接口", desc: "优化前强制门禁", color: C.gold },
    { num: "98%", label: "成本权重", desc: "以商务成本为核心", color: C.red },
  ];
  vals.forEach((v, i) => {
    const x = 0.5 + i * 3.15;
    addCard(s, x, 1.5, 2.95, 2.0, { accentColor: v.color });
    s.addText(v.num, { x, y: 1.7, w: 2.95, h: 0.8, fontSize: 40, bold: true, color: v.color, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addText(v.label, { x, y: 2.5, w: 2.95, h: 0.35, fontSize: 13, bold: true, color: C.darkGray, fontFace: "Calibri", align: "center", margin: 0 });
    s.addText(v.desc, { x, y: 2.85, w: 2.95, h: 0.4, fontSize: 10, color: C.gray, fontFace: "Calibri", align: "center", margin: 0 });
  });

  // 下半部分：技术壁垒
  addCard(s, 0.5, 3.8, 12.3, 2.9, { accentColor: C.navy });
  s.addText("技术壁垒", { x: 0.8, y: 3.95, w: 5, h: 0.4, fontSize: 16, bold: true, color: C.navy, fontFace: "Calibri", margin: 0 });
  const barriers = [
    { t: "组合爆炸求解能力", d: "遗传算法 + 自适应策略（枚举/遗传切换），可处理 10^30+ 组合空间" },
    { t: "工程约束建模", d: "组团变化、互斥约束、多选一、资源数量限制、直连接口，覆盖真实设计场景" },
    { t: "计算与优化解耦架构", d: "回路计算引擎独立复用，三大优化算法共享同一成本逻辑，结果可追溯" },
    { t: "多线程并行计算", d: "ThreadPool + ConcurrentHashMap 原子去重，万级方案分钟级完成" },
    { t: "全链路质量门禁", d: "8大检查接口，Error阻断 + Warning提示，保障优化输入质量" },
    { t: "可中断可恢复", d: "OptimizeStopStatusStore 单例状态管理，支持随时中断与结果保留" },
  ];
  barriers.forEach((b, i) => {
    const col = i % 3, row = Math.floor(i / 3);
    const x = 0.8 + col * 4.1, y = 4.4 + row * 1.05;
    s.addShape(pres.shapes.RECTANGLE, { x, y, w: 0.06, h: 0.85, fill: { color: C.accent } });
    s.addText(b.t, { x: x + 0.15, y, w: 3.8, h: 0.3, fontSize: 11.5, bold: true, color: C.darkGray, fontFace: "Calibri", margin: 0 });
    s.addText(b.d, { x: x + 0.15, y: y + 0.3, w: 3.8, h: 0.55, fontSize: 9.5, color: C.gray, fontFace: "Calibri", margin: 0 });
  });
  addFooter(s, 21);
}

// ===== 第22页：演进路线与结语 =====
{
  const s = pres.addSlide();
  s.background = { color: C.lightBg };
  addTitleBar(s, "演进路线与展望", "从规则驱动到数据驱动 · 从单点优化到全局智能");

  // 路线图
  const roadmap = [
    { phase: "当前", title: "算法平台已就绪", items: ["四大算法引擎落地", "8类质量检查门禁", "Top20方案推荐"], color: C.green },
    { phase: "近期", title: "AI 能力增强", items: ["AI推理引擎加速收敛", "历史方案学习与推荐", "优化过程可视化"], color: C.accent },
    { phase: "中期", title: "全局协同优化", items: ["拓扑+位置+配电联合优化", "多车型方案复用", "成本预测模型"], color: C.gold },
    { phase: "远期", title: "智能设计平台", items: ["自动生成初始拓扑", "设计规则自动学习", "竞品方案对标分析"], color: C.red },
  ];
  const startY = 1.6, blockW = 2.9, gap = 0.2;
  roadmap.forEach((r, i) => {
    const x = 0.5 + i * (blockW + gap);
    addCard(s, x, startY, blockW, 4.0, { accentColor: r.color });
    s.addShape(pres.shapes.RECTANGLE, { x, y: startY, w: blockW, h: 0.6, fill: { color: r.color } });
    s.addText(r.phase, { x, y: startY, w: blockW, h: 0.6, fontSize: 14, bold: true, color: C.white, fontFace: "Calibri", align: "center", valign: "middle", margin: 0 });
    s.addText(r.title, { x: x + 0.15, y: startY + 0.7, w: blockW - 0.3, h: 0.5, fontSize: 13, bold: true, color: C.darkGray, fontFace: "Calibri", align: "center", margin: 0 });
    r.items.forEach((it, j) => {
      s.addText("● " + it, { x: x + 0.2, y: startY + 1.3 + j * 0.6, w: blockW - 0.4, h: 0.5, fontSize: 11, color: C.darkGray, fontFace: "Calibri", margin: 0 });
    });
    if (i < roadmap.length - 1) {
      s.addShape(pres.shapes.RECTANGLE, { x: x + blockW + 0.02, y: startY + 1.9, w: gap - 0.04, h: 0.08, fill: { color: C.navy } });
    }
  });

  // 结语
  addCard(s, 0.5, 5.9, 12.3, 1.0, { accentColor: C.navy });
  s.addText("以算法驱动降本增效，让每一米导线、每一个接插件都物尽其用", { x: 0.8, y: 6.0, w: 12, h: 0.4, fontSize: 15, bold: true, color: C.navy, fontFace: "Calibri", align: "center", margin: 0 });
  s.addText("整车线束智能优化算法平台 · 技术产品汇报 · 2026年8月", { x: 0.8, y: 6.45, w: 12, h: 0.35, fontSize: 11, color: C.gray, fontFace: "Calibri", align: "center", italic: true, margin: 0 });
  addFooter(s, 22);
}

module.exports = {};
