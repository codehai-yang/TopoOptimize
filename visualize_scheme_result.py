# -*- coding: utf-8 -*-
"""
配电驱动优化结果可视化
读取算法返回的 top20 方案 JSON,选择某个方案,生成交互式回路连接图
标注哪些回路的导线选型相对 base 方案发生了更新

用法:
  python visualize_scheme_result.py --result 结果.json --param 入参.json
  python visualize_scheme_result.py --result 结果.json --param 入参.json --index 0
  python visualize_scheme_result.py --result 结果.json --param 入参.json --index 0 --output scheme.html
"""
import json
import os
import sys
import argparse
from collections import deque, defaultdict

TYPE_COLOR = {
    "发电单元": "#2ecc71",
    "储电单元": "#3498db",
    "配电单元": "#e67e22",
    "控制器": "#9b59b6",
    "用电器": "#95a5a6",
}


def load_json(path):
    """加载JSON文件,容错处理日志文件(含多余文本)"""
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    # 先尝试直接解析
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        pass
    # 失败则提取第一个完整JSON块(从第一个{到匹配的})
    start = content.find("{")
    if start == -1:
        start = content.find("[")
    if start == -1:
        raise ValueError(f"文件中未找到JSON内容: {path}")
    decoder = json.JSONDecoder()
    obj, end = decoder.raw_decode(content[start:])
    return obj


def extract_app_type_map(param_json):
    """从入参JSON的appPositions列表提取 appName -> appType 映射"""
    app_type_map = {}
    apps = param_json.get("appPositions", [])
    for app in apps:
        name = app.get("appName", "")
        typ = app.get("appType", "用电器")
        if name:
            app_type_map[name] = typ
    return app_type_map


def extract_loops_from_scheme(scheme):
    """从方案提取回路列表
    优先级:
    1. loopInfos 字段(英文键名,Java端enrichToFullScheme保留的)
    2. circuitInfo 字段(中文键名,整车计算结果的回路详情列表)
    3. projectCircuitInfo 嵌套结构(回退)
    """
    # 1. loopInfos 字段(英文键名: id, startApp, endApp, loopWireway, loopAttr)
    loop_infos = scheme.get("loopInfos")
    if loop_infos and isinstance(loop_infos, list) and len(loop_infos) > 0:
        return loop_infos
    # 2. circuitInfo 字段(中文键名: 回路id, 起点用电器名称, 终点用电器名称, 导线选型, 回路属性)
    circuit_info = scheme.get("circuitInfo")
    if circuit_info and isinstance(circuit_info, list) and len(circuit_info) > 0:
        return circuit_info
    # 3. 回退: projectCircuitInfo 嵌套结构
    all_loops = []
    proj_circuit_info = scheme.get("projectCircuitInfo", {})
    if isinstance(proj_circuit_info, dict):
        for key, loops in proj_circuit_info.items():
            if isinstance(loops, list):
                for loop in loops:
                    if isinstance(loop, dict):
                        all_loops.append(loop)
            elif isinstance(loops, dict):
                all_loops.append(loops)
    elif isinstance(proj_circuit_info, list):
        all_loops = proj_circuit_info
    return all_loops


def normalize_loop(loop):
    """统一回路字段为标准格式,支持英文和中文两种键名"""
    return {
        "id": loop.get("回路id") or loop.get("id") or "",
        "startApp": loop.get("起点用电器名称") or loop.get("回路起点用电器") or loop.get("startApp") or "",
        "endApp": loop.get("终点用电器名称") or loop.get("回路终点用电器") or loop.get("endApp") or "",
        "loopWireway": loop.get("导线选型") or loop.get("回路导线选型") or loop.get("loopWireway") or "",
        "loopAttr": loop.get("回路属性") or loop.get("loopAttr") or "",
    }


def build_adjacency(loops):
    """构建双向邻接表"""
    adj = defaultdict(list)
    for loop in loops:
        s = loop["startApp"]
        e = loop["endApp"]
        adj[s].append((e, loop))
        adj[e].append((s, loop))
    return adj


def find_root(app_type_map, adjacency):
    """找根节点: 优先发电单元,其次储电单元,最后第一个节点"""
    gen_nodes = [n for n, t in app_type_map.items() if t == "发电单元"]
    if gen_nodes:
        return gen_nodes[0]
    storage_nodes = [n for n, t in app_type_map.items() if t == "储电单元"]
    if storage_nodes:
        return storage_nodes[0]
    if adjacency:
        return next(iter(adjacency))
    return None


def build_tree(root, adjacency):
    """BFS 建树,返回 (children_map, parent_map, parent_loop_map, all_edges, depth_map)"""
    if root is None:
        return {}, {}, {}, [], {}
    visited = {root}
    queue = deque([root])
    children = defaultdict(list)
    parent_map = {}
    parent_loop_map = {}
    depth_map = {root: 0}
    all_edges = []

    while queue:
        node = queue.popleft()
        for neighbor, loop in adjacency.get(node, []):
            all_edges.append((node, neighbor, loop))
            if neighbor not in visited:
                visited.add(neighbor)
                children[node].append((neighbor, loop))
                parent_map[neighbor] = node
                parent_loop_map[neighbor] = loop
                depth_map[neighbor] = depth_map[node] + 1
                queue.append(neighbor)

    return dict(children), parent_map, parent_loop_map, all_edges, depth_map


def compare_wire_selection(base_loops, scheme_loops):
    """对比 base 和选中方案的导线选型,返回更新的回路 id 集合和详情"""
    base_wire_map = {}
    for loop in base_loops:
        lid = loop["id"]
        if lid:
            base_wire_map[lid] = loop["loopWireway"]

    updates = {}
    for loop in scheme_loops:
        lid = loop["id"]
        if not lid:
            continue
        base_wire = base_wire_map.get(lid, "")
        cur_wire = loop["loopWireway"]
        if base_wire and cur_wire and base_wire != cur_wire:
            updates[lid] = {
                "old_wire": base_wire,
                "new_wire": cur_wire,
            }
    return updates


def count_leaves(children, node):
    """递归计算叶子数"""
    kids = children.get(node, [])
    if not kids:
        return 1
    return sum(count_leaves(children, c) for c, _ in kids)


def tree_depth(children, node, depth=0):
    """递归计算树深度"""
    kids = children.get(node, [])
    if not kids:
        return depth
    return max(tree_depth(children, c, depth + 1) for c, _ in kids)


def hierarchy_pos(children, root, width=1.0, vert_gap=120):
    """叶子加权层次布局,根在上,子在下"""
    pos = {}

    def _layout(node, left, right, depth):
        pos[node] = ((left + right) / 2, depth * vert_gap)
        kids = children.get(node, [])
        if not kids:
            return
        total_leaves = sum(count_leaves(children, c) for c, _ in kids)
        cur_left = left
        for child, _ in kids:
            child_leaves = count_leaves(children, child)
            child_width = (right - left) * child_leaves / total_leaves
            _layout(child, cur_left, cur_left + child_width, depth + 1)
            cur_left += child_width

    _layout(root, 0, width, 0)
    return pos


def svg_escape(text):
    if not text:
        return ""
    text = str(text)
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace('"', "&quot;").replace("'", "&#39;")
    return text


def generate_html(root, children, app_type_map, updates, all_edges, scheme_cost,
                  scheme_index, total_schemes, output, is_base):
    """生成交互式 HTML"""
    leaf_cnt = count_leaves(children, root) if root else 1
    depth = tree_depth(children, root) if root else 1
    h_unit = 260
    pos = hierarchy_pos(children, root, width=leaf_cnt * h_unit, vert_gap=120) if root else {}

    xs = [p[0] for p in pos.values()] if pos else [0]
    ys = [p[1] for p in pos.values()] if pos else [0]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    pad = 80
    svg_w = max_x - min_x + 2 * pad
    svg_h = max_y - min_y + 2 * pad
    norm = {n: (p[0] - min_x + pad, p[1] - min_y + pad) for n, p in pos.items()}

    # 邻接关系 JSON
    adj_json = defaultdict(list)
    for parent, child, loop in all_edges:
        adj_json[parent].append({"to": child, "wire": loop["loopWireway"], "id": loop["id"]})

    svg_elems = []

    # 画边
    drawn_edges = set()
    for parent, child, loop in all_edges:
        if parent not in norm or child not in norm:
            continue
        edge_key = (parent, child)
        if edge_key in drawn_edges:
            continue
        drawn_edges.add(edge_key)
        x1, y1 = norm[parent]
        x2, y2 = norm[child]
        mid_x = (x1 + x2) / 2
        path = f"M {x1:.1f},{y1:.1f} C {mid_x:.1f},{y1:.1f} {mid_x:.1f},{y2:.1f} {x2:.1f},{y2:.1f}"
        wire = loop["loopWireway"]
        loop_id = loop["id"]
        upd = updates.get(loop_id)
        if upd:
            label_text = f"{svg_escape(upd['old_wire'])} → {svg_escape(upd['new_wire'])}"
            color, lw = "#e74c3c", 3
        else:
            label_text = svg_escape(wire)
            color, lw = "#95a5a6", 1.5
        eid = f"edge_{parent}__{child}"
        svg_elems.append(f'<path id="{eid}" class="edge" d="{path}" stroke="{color}" stroke-width="{lw}" '
                         f'fill="none" data-from="{svg_escape(parent)}" data-to="{svg_escape(child)}"/>')
        lx, ly = mid_x, (y1 + y2) / 2
        lw_px = len(label_text) * 5.5 + 8
        svg_elems.append(f'<rect class="edge-label-bg" x="{lx-lw_px/2:.1f}" y="{ly-8:.1f}" width="{lw_px:.1f}" height="16" '
                         f'fill="white" opacity="0.88" rx="3" pointer-events="none"/>')
        svg_elems.append(f'<text class="edge-label" x="{lx:.1f}" y="{ly+4:.1f}" text-anchor="middle" '
                         f'font-size="10" fill="{color}" font-weight="{"bold" if upd else "normal"}" pointer-events="none">'
                         f'{label_text}</text>')

    # 画节点
    for node_name, (x, y) in norm.items():
        typ = app_type_map.get(node_name, "用电器")
        color = TYPE_COLOR.get(typ, "#bdc3c7")
        name_esc = svg_escape(node_name)
        type_esc = svg_escape(typ)
        rect_w = max(len(node_name) * 9, len(typ) * 7, 70) + 20
        rect_h = 34
        nid = f"node_{node_name}"
        svg_elems.append(f'<g id="{nid}" class="node" data-name="{name_esc}" data-type="{type_esc}" '
                         f'style="cursor:pointer;">')
        svg_elems.append(f'<rect x="{x-rect_w/2:.1f}" y="{y-rect_h/2:.1f}" width="{rect_w:.1f}" height="{rect_h}" '
                         f'rx="6" fill="{color}" stroke="#2c3e50" stroke-width="1.5"/>')
        svg_elems.append(f'<text x="{x:.1f}" y="{y-3:.1f}" text-anchor="middle" font-size="11" '
                         f'fill="white" font-weight="bold" pointer-events="none">{name_esc}</text>')
        svg_elems.append(f'<text x="{x:.1f}" y="{y+10:.1f}" text-anchor="middle" font-size="9" '
                         f'fill="white" opacity="0.85" pointer-events="none">{type_esc}</text>')
        svg_elems.append('</g>')

    svg_content = "\n  ".join(svg_elems)
    update_count = len(updates)
    cost_str = ""
    if scheme_cost:
        cost_str = f"总成本={scheme_cost.get('总成本', '?')} 重量={scheme_cost.get('总重量', '?')} 长度={scheme_cost.get('总长度', '?')}"

    scheme_label = "base方案" if is_base else f"方案{scheme_index}"
    title_suffix = f" | {update_count} 个回路导线选型被更新(红色)" if update_count else " | 无导线选型更新"

    adj_json_str = json.dumps(dict(adj_json), ensure_ascii=False)

    html = f"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>配电驱动优化方案可视化</title>
<style>
  * {{ margin:0; padding:0; box-sizing:border-box; }}
  body {{ font-family:'Microsoft YaHei','SimHei',sans-serif; background:#fafafa; overflow:hidden; }}
  #toolbar {{ position:fixed; top:10px; left:10px; z-index:10; background:rgba(255,255,255,0.95);
              padding:8px 12px; border-radius:6px; box-shadow:0 1px 4px rgba(0,0,0,0.15); }}
  #toolbar button {{ margin-right:6px; padding:4px 10px; cursor:pointer; border:1px solid #bdc3c7;
                     background:#fff; border-radius:4px; font-size:12px; }}
  #toolbar button:hover {{ background:#ecf0f1; }}
  #legend {{ position:fixed; top:10px; right:10px; z-index:10; background:rgba(255,255,255,0.95);
             padding:10px 14px; border-radius:6px; box-shadow:0 1px 4px rgba(0,0,0,0.15); font-size:12px; }}
  #legend .item {{ display:flex; align-items:center; margin:3px 0; }}
  #legend .swatch {{ width:14px; height:14px; border-radius:3px; margin-right:6px; border:1px solid #2c3e50; }}
  #title {{ position:fixed; bottom:10px; left:50%; transform:translateX(-50%); z-index:10;
            background:rgba(255,255,255,0.95); padding:6px 16px; border-radius:6px;
            box-shadow:0 1px 4px rgba(0,0,0,0.15); font-size:13px; font-weight:bold; }}
  #info {{ position:fixed; top:50px; right:10px; z-index:11; background:rgba(255,255,255,0.98);
           padding:10px 14px; border-radius:6px; box-shadow:0 2px 8px rgba(0,0,0,0.2); font-size:12px;
           max-width:320px; max-height:70vh; overflow-y:auto; display:none; }}
  #info h4 {{ margin-bottom:6px; color:#2c3e50; }}
  #info .row {{ margin:2px 0; }}
  #info .wire {{ color:#e74c3c; font-weight:bold; }}
  svg {{ display:block; cursor:grab; width:100vw; height:100vh; }}
  svg:active {{ cursor:grabbing; }}
  .node.dim rect {{ opacity:0.25; }}
  .node.dim text {{ opacity:0.25; }}
  .edge.dim {{ opacity:0.1 !important; }}
  .edge-label-bg.dim, .edge-label.dim {{ opacity:0.1 !important; }}
  .node.highlight rect {{ stroke:#f39c12; stroke-width:3.5; filter:drop-shadow(0 0 6px #f39c12); }}
</style>
</head>
<body>
<div id="toolbar">
  <button onclick="zoomFit()">适应窗口</button>
  <button onclick="zoomIn()">放大</button>
  <button onclick="zoomOut()">缩小</button>
  <button onclick="resetHighlight()" style="background:#e8f4fd;border-color:#3498db;">重置高亮</button>
  <span style="margin-left:8px;color:#7f8c8d;font-size:11px;">点击节点查看下游 | 滚轮缩放 | 拖拽平移</span>
</div>
<div id="legend">
  <div class="item"><span class="swatch" style="background:#2ecc71"></span>发电单元</div>
  <div class="item"><span class="swatch" style="background:#3498db"></span>储电单元</div>
  <div class="item"><span class="swatch" style="background:#e67e22"></span>配电单元</div>
  <div class="item"><span class="swatch" style="background:#9b59b6"></span>控制器</div>
  <div class="item"><span class="swatch" style="background:#95a5a6"></span>用电器</div>
  <div class="item"><span class="swatch" style="background:#e74c3c"></span>导线选型已更新</div>
</div>
<div id="title">{scheme_label} (共{total_schemes}个方案) - {cost_str}{title_suffix}</div>
<div id="info"></div>
<svg id="treeSvg" viewBox="0 0 {svg_w:.1f} {svg_h:.1f}">
  <g id="treeGroup">
  {svg_content}
  </g>
</svg>
<script>
const adjData = {adj_json_str};
const initialVB = {{ x:0, y:0, w:{svg_w:.1f}, h:{svg_h:.1f} }};
let vb = {{ ...initialVB }};
const svgEl = document.getElementById('treeSvg');
function updateVB() {{ svgEl.setAttribute('viewBox', vb.x + ' ' + vb.y + ' ' + vb.w + ' ' + vb.h); }}
function zoomAt(cx, cy, factor) {{
  vb.w *= factor; vb.h *= factor;
  vb.x = cx - (cx - vb.x) * factor;
  vb.y = cy - (cy - vb.y) * factor;
  updateVB();
}}
function zoomFit() {{ vb = {{ ...initialVB }}; updateVB(); }}
function zoomIn() {{ zoomAt(vb.x + vb.w/2, vb.y + vb.h/2, 0.8); }}
function zoomOut() {{ zoomAt(vb.x + vb.w/2, vb.y + vb.h/2, 1.25); }}
svgEl.addEventListener('wheel', function(e) {{
  e.preventDefault();
  const rect = svgEl.getBoundingClientRect();
  const sx = (e.clientX - rect.left) / rect.width * vb.w + vb.x;
  const sy = (e.clientY - rect.top) / rect.height * vb.h + vb.y;
  const factor = e.deltaY > 0 ? 1.15 : 0.87;
  zoomAt(sx, sy, factor);
}});
let dragging = false, sx = 0, sy = 0, startVB = null, dragMoved = false;
svgEl.addEventListener('mousedown', function(e) {{
  dragging = true; dragMoved = false; sx = e.clientX; sy = e.clientY; startVB = {{ ...vb }};
}});
window.addEventListener('mousemove', function(e) {{
  if (!dragging) return;
  if (Math.abs(e.clientX - sx) > 3 || Math.abs(e.clientY - sy) > 3) dragMoved = true;
  const rect = svgEl.getBoundingClientRect();
  vb.x = startVB.x - (e.clientX - sx) * vb.w / rect.width;
  vb.y = startVB.y - (e.clientY - sy) * vb.h / rect.height;
  updateVB();
}});
window.addEventListener('mouseup', function() {{ dragging = false; }});

function bfsDownstream(start) {{
  const visited = new Set([start]);
  const queue = [start];
  const edges = [];
  while (queue.length) {{
    const node = queue.shift();
    const neighbors = adjData[node] || [];
    for (const n of neighbors) {{
      edges.push({{from: node, to: n.to, wire: n.wire}});
      if (!visited.has(n.to)) {{ visited.add(n.to); queue.push(n.to); }}
    }}
  }}
  return {{ nodes: visited, edges: edges }};
}}
function highlightDownstream(nodeName) {{
  const result = bfsDownstream(nodeName);
  document.querySelectorAll('.node').forEach(g => g.classList.add('dim'));
  document.querySelectorAll('.edge, .edge-label-bg, .edge-label').forEach(e => e.classList.add('dim'));
  result.nodes.forEach(n => {{
    const g = document.getElementById('node_' + n);
    if (g) {{ g.classList.remove('dim'); g.classList.add('highlight'); }}
  }});
  result.edges.forEach(e => {{
    const path = document.getElementById('edge_' + e.from + '__' + e.to);
    if (path) path.classList.remove('dim');
  }});
  const info = document.getElementById('info');
  const childNodes = [...result.nodes].filter(n => n !== nodeName);
  let html = '<h4>' + nodeName + ' 下游回路</h4>';
  html += '<div class="row">下游节点数: <b>' + childNodes.length + '</b></div>';
  html += '<div class="row">下游回路数: <b>' + result.edges.length + '</b></div>';
  html += '<hr style="margin:6px 0;border:none;border-top:1px solid #ecf0f1;">';
  const grouped = {{}};
  result.edges.forEach(e => {{ grouped[e.from + ' -> ' + e.to] = e.wire; }});
  Object.keys(grouped).sort().forEach(k => {{
    html += '<div class="row">' + k + ' <span class="wire">' + grouped[k] + '</span></div>';
  }});
  info.innerHTML = html;
  info.style.display = 'block';
}}
function resetHighlight() {{
  document.querySelectorAll('.node').forEach(g => {{ g.classList.remove('dim'); g.classList.remove('highlight'); }});
  document.querySelectorAll('.edge, .edge-label-bg, .edge-label').forEach(e => e.classList.remove('dim'));
  document.getElementById('info').style.display = 'none';
}}
document.querySelectorAll('.node').forEach(g => {{
  g.addEventListener('click', function(e) {{
    if (dragMoved) return;
    const name = g.getAttribute('data-name');
    document.querySelectorAll('.node').forEach(n => n.classList.remove('highlight'));
    highlightDownstream(name);
    e.stopPropagation();
  }});
}});
svgEl.addEventListener('click', function(e) {{
  if (dragMoved) return;
  if (e.target.tagName === 'svg' || e.target.id === 'treeGroup') resetHighlight();
}});
updateVB();
</script>
</body>
</html>"""
    with open(output, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"HTML 已生成: {output}")


def main():
    parser = argparse.ArgumentParser(description="配电驱动优化结果可视化")
    parser.add_argument("--result", required=False,
                        default=r"F:\office\idearProjects\project20251009\src\main\resources\powerOutput.json",
                        help="算法返回结果 JSON 文件")
    parser.add_argument("--param", required=False,
                        default=r"F:\office\idearProjects\project20251009\src\main\resources\电源分配优化日志.txt",
                        help="算法入参 JSON 文件(用于获取用电器类型)")
    parser.add_argument("--index", type=int, default=2,
                        help="选择第几个方案(从0开始)")
    parser.add_argument("--output", default=None, help="输出 HTML 路径")
    args = parser.parse_args()

    # 读取返回结果
    if not os.path.exists(args.result):
        print(f"错误: 结果文件不存在: {args.result}")
        sys.exit(1)
    schemes = load_json(args.result)
    if not isinstance(schemes, list):
        print("错误: 结果文件不是方案列表(JSON数组)")
        sys.exit(1)

    print(f"共 {len(schemes)} 个方案:")
    for i, s in enumerate(schemes):
        cost = s.get("成本", {})
        total = cost.get("总成本", "?")
        is_init = s.get("initializationScheme", False)
        tag = " [base]" if is_init else ""
        print(f"  [{i}] 总成本={total}{tag}")

    # 读取入参获取 appType
    app_type_map = {}
    if os.path.exists(args.param):
        param_json = load_json(args.param)
        app_type_map = extract_app_type_map(param_json)
        print(f"从入参读取 {len(app_type_map)} 个用电器类型")
    else:
        print("警告: 未找到入参文件,用电器类型将默认为'用电器'")

    # 选择方案
    if args.index < 0 or args.index >= len(schemes):
        print(f"错误: 方案索引超出范围(0-{len(schemes)-1})")
        sys.exit(1)
    scheme = schemes[args.index]
    is_base = scheme.get("initializationScheme", False)
    scheme_label = "base方案" if is_base else f"方案{args.index}"
    print(f"\n已选择: {scheme_label}")

    # 提取回路
    raw_loops = extract_loops_from_scheme(scheme)
    loops = [normalize_loop(l) for l in raw_loops]
    print(f"提取到 {len(loops)} 条回路")

    # 找 base 方案用于对比
    base_scheme = None
    for s in schemes:
        if s.get("initializationScheme", False):
            base_scheme = s
            break
    if base_scheme is None and schemes:
        base_scheme = schemes[-1]

    # 对比导线选型
    updates = {}
    if base_scheme is not None and not is_base:
        base_raw_loops = extract_loops_from_scheme(base_scheme)
        base_loops = [normalize_loop(l) for l in base_raw_loops]
        updates = compare_wire_selection(base_loops, loops)
        print(f"与 base 方案对比: {len(updates)} 条回路导线选型发生变化")
    else:
        print("当前即为 base 方案,无需对比")

    # 打印更新详情
    if updates:
        print("\n导线选型变更详情:")
        for lid, info in updates.items():
            print(f"  回路 {lid}: {info['old_wire']} → {info['new_wire']}")

    # 建图
    adjacency = build_adjacency(loops)
    root = find_root(app_type_map, adjacency)
    if root is None:
        print("错误: 无法确定根节点")
        sys.exit(1)
    print(f"根节点: {root} ({app_type_map.get(root, '用电器')})")

    children, parent_map, parent_loop_map, all_edges, depth_map = build_tree(root, adjacency)
    print(f"树节点数: {len(children) + 1}, 回路连接数: {len(all_edges)}")

    # 生成 HTML
    output = args.output or "scheme_result.html"
    scheme_cost = scheme.get("成本", {})
    generate_html(root, children, app_type_map, updates, all_edges,
                  scheme_cost, args.index, len(schemes), output, is_base)


if __name__ == "__main__":
    main()
