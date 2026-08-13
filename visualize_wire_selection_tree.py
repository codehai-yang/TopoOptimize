# -*- coding: utf-8 -*-
"""
配电驱动优化 - 回路导线选型树状结构可视化工具

读取算法入参 JSON(含 loopInfos 和 appPositions)，
复刻 Java 端 updateWireSelectionForScheme 的建树逻辑：
  1) 构建 appTypeMap(appName -> appType)
  2) 构建邻接表(双向)
  3) 以发电单元(无则储电单元)为根 BFS 建有向树
  4) 自底向上反向 BFS 模拟导线选型更新(仅打印,不改数据)

画出有向树:
  - 节点按类型着色(发电=绿/储电=蓝/配电=橙/控制器=紫/用电器=灰)
  - 边上标注该回路的导线选型(原 loopWireway)
  - 父回路更新后的选型用 [->新选型] 标注

用法:
  python visualize_wire_selection_tree.py --input input.json
  python visualize_wire_selection_tree.py --input input.json --output tree.png
  python visualize_wire_selection_tree.py --input input.json --type 5
"""
import json
import argparse
import re

import networkx as nx
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

# 中文显示
plt.rcParams['font.sans-serif'] = ['SimHei', 'Microsoft YaHei', 'SimSun', 'Arial Unicode MS']
plt.rcParams['axes.unicode_minus'] = False

# 节点类型颜色
TYPE_COLOR = {
    '发电单元': '#2ecc71',
    '储电单元': '#3498db',
    '配电单元': '#e67e22',
    '控制器': '#9b59b6',
    '用电器': '#95a5a6',
}


def load_input(path):
    """读取算法入参 JSON(支持 txt 末尾被污染的 raw_decode)"""
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    try:
        return json.loads(content)
    except Exception:
        decoder = json.JSONDecoder()
        obj, _ = decoder.raw_decode(content)
        return obj


def extract_wire_gauge(wire_type):
    """复刻 Java extractWireGauge: 从选型字符串末段提取数字(如 'FLRY-B 0.35' -> 0.35)"""
    if not wire_type:
        return None
    m = re.search(r'(\d+(?:\.\d+)?)\s*$', wire_type.strip())
    if m:
        try:
            return float(m.group(1))
        except ValueError:
            return None
    return None


def apply_coefficient(total, c0=0.7, c45=0.6, c90=0.5):
    """复刻 Java applyWireCoefficient"""
    if total is None or total <= 0:
        return None
    if total <= 45:
        return total * c0
    elif total <= 90:
        return total * c45
    else:
        return total * c90


def build_tree(data):
    """复刻 Java updateWireSelectionForScheme 的建树逻辑(BFS+visited去重)
    同时返回 all_edges 用于可视化所有回路连接"""
    loop_infos = data.get('loopInfos', [])
    app_positions = data.get('appPositions', [])

    # appTypeMap
    app_type_map = {}
    for app in app_positions:
        name = app.get('appName')
        typ = app.get('appType')
        if name and typ:
            app_type_map[name] = typ

    # 邻接表(双向)
    adj = {}
    for loop in loop_infos:
        s = loop.get('startApp')
        e = loop.get('endApp')
        if not s or not e:
            continue
        adj.setdefault(s, []).append((e, loop))
        adj.setdefault(e, []).append((s, loop))

    # 找根: 优先发电单元, 无则储电单元
    root = None
    for name, typ in app_type_map.items():
        if typ == '发电单元':
            root = name
            break
    if not root:
        for name, typ in app_type_map.items():
            if typ == '储电单元':
                root = name
                break
    if not root:
        return None, None, None, None, []

    # BFS 建有向树(与 Java 一致, visited 去重)
    tree_children = {}
    parent_loop = {}
    visited = [root]
    queue = [root]
    depth_map = {root: 0}
    while queue:
        node = queue.pop(0)
        for neighbor, loop in adj.get(node, []):
            if neighbor not in visited:
                visited.append(neighbor)
                tree_children.setdefault(node, []).append(neighbor)
                parent_loop[neighbor] = loop
                depth_map[neighbor] = depth_map[node] + 1
                queue.append(neighbor)

    # 构建所有边: 按深度方向定向, 一条回路可能产生多条边(用电器连多配电单元)
    all_edges = []  # [(parent, child, loop)]
    for loop in loop_infos:
        s = loop.get('startApp')
        e = loop.get('endApp')
        if not s or not e or s not in depth_map or e not in depth_map:
            continue
        if depth_map[s] <= depth_map[e]:
            all_edges.append((s, e, loop))
        else:
            all_edges.append((e, s, loop))

    return root, tree_children, parent_loop, app_type_map, all_edges


def simulate_wire_update(root, tree_children, parent_loop, app_type_map, optimize_type):
    """复刻 Java 自底向上反向BFS的导线选型更新(仅模拟,返回更新前后的选型对照)"""
    if optimize_type not in ('3', '5'):
        return {}

    # 反向BFS序(自底向上)
    bfs_order = []
    queue = [root]
    visited = set([root])
    while queue:
        node = queue.pop(0)
        bfs_order.append(node)
        for child in tree_children.get(node, []):
            if child not in visited:
                visited.add(child)
                queue.append(child)
    bfs_order.reverse()

    updates = {}  # child_app -> (old_wire, new_wire, sum_gauge, coeff, calc_value)
    for node in bfs_order:
        typ = app_type_map.get(node, '')
        # 先找 parent 节点
        parent_node = None
        for p, kids in tree_children.items():
            if node in kids:
                parent_node = p
                break
        if parent_node is None:
            continue
        parent_typ = app_type_map.get(parent_node, '')
        # 按优化类型限制处理范围(与 Java updateWireSelectionForScheme 一致):
        # 类型3: 仅更新 配电单元(node) -> 配电单元(parent) 回路
        # 类型5: 更新 配电单元(node) -> 配电单元(parent) 和 控制器(node) -> 配电单元(parent) 回路
        if optimize_type == '3':
            if not (typ == '配电单元' and parent_typ == '配电单元'):
                continue
        elif optimize_type == '5':
            if not ((typ == '配电单元' and parent_typ == '配电单元')
                    or (typ == '控制器' and parent_typ == '配电单元')):
                continue
        else:
            continue
        children = tree_children.get(node, [])
        if not children:
            continue
        sum_gauge = 0.0
        for child in children:
            child_typ = app_type_map.get(child, '')
            loop = parent_loop.get(child)
            if not loop:
                continue
            wire = loop.get('loopWireway', '')
            g = extract_wire_gauge(wire) or 0.0
            sum_gauge += g
        # 应用系数
        calc_value = apply_coefficient(sum_gauge)
        if calc_value is None:
            continue
        parent_loop_info = parent_loop.get(node)
        if not parent_loop_info:
            continue
        old_wire = parent_loop_info.get('loopWireway', '?')
        updates[node] = {
            'parent': parent_node,
            'old_wire': old_wire,
            'sum_gauge': sum_gauge,
            'calc_value': calc_value,
            'children_gauges': [
                (child, parent_loop.get(child, {}).get('loopWireway', '?'),
                 extract_wire_gauge(parent_loop.get(child, {}).get('loopWireway', '')) or 0.0)
                for child in children
            ]
        }
    return updates


def _count_leaves(G, node):
    """统计以 node 为根的子树叶子数(用于层次布局按叶子加权分配宽度)"""
    children = list(G.successors(node))
    if not children:
        return 1
    return sum(_count_leaves(G, c) for c in children)


def hierarchy_pos(G, root, width=1.0, vert_gap=1.5):
    """按叶子数加权分配宽度的层次树布局(垂直树: 根在上,子在下)
    x = 水平位置(同层节点偏移), y = depth * vert_gap(正值,根在顶部)"""
    pos = {}

    def _h(node, left, right, depth):
        pos[node] = ((left + right) / 2, depth * vert_gap)
        children = list(G.successors(node))
        if not children:
            return
        # 按子树叶子数加权分配宽度
        child_leaves = [max(_count_leaves(G, c), 1) for c in children]
        total = sum(child_leaves)
        cur = left
        for child, cl in zip(children, child_leaves):
            cw = (right - left) * cl / total
            _h(child, cur, cur + cw, depth + 1)
            cur += cw

    _h(root, 0.0, width, 0)
    return pos


def _tree_depth(G, root):
    """计算树的最大深度"""
    def _d(node):
        children = list(G.successors(node))
        if not children:
            return 1
        return 1 + max(_d(c) for c in children)
    return _d(root)


def build_tree_json(root, tree_children, parent_loop, app_type_map, updates):
    """把树转成 d3.hierarchy 需要的嵌套 JSON"""
    def _build(node):
        typ = app_type_map.get(node, '?')
        loop = parent_loop.get(node, {})
        wire = loop.get('loopWireway', '?')
        upd = updates.get(node)
        result = {
            'name': node,
            'type': typ,
            'wire': wire,
            'updated': bool(upd),
            'oldWire': upd['old_wire'] if upd else None,
        }
        children = tree_children.get(node, [])
        if children:
            result['children'] = [_build(c) for c in children]
        return result
    return _build(root)


def _svg_escape(text):
    """转义 SVG 文本中的特殊字符"""
    return str(text).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def generate_html(root, tree_children, parent_loop, app_type_map, updates, output, optimize_type, all_edges):
    """生成交互式 HTML,点击节点高亮其下游所有回路"""
    G = nx.DiGraph()
    for node, typ in app_type_map.items():
        G.add_node(node, ntype=typ)
    for parent, children in tree_children.items():
        for child in children:
            G.add_edge(parent, child)

    leaf_cnt = _count_leaves(G, root)
    vert_gap = 120
    h_unit = 260
    pos = hierarchy_pos(G, root, width=leaf_cnt * h_unit, vert_gap=vert_gap)

    xs = [p[0] for p in pos.values()]
    ys = [p[1] for p in pos.values()]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    pad = 80
    svg_w = max_x - min_x + 2 * pad
    svg_h = max_y - min_y + 2 * pad
    norm = {n: (p[0] - min_x + pad, p[1] - min_y + pad) for n, p in pos.items()}

    # 构建邻接关系JSON(用于JS高亮)
    adj_json = {}
    for parent, child, loop in all_edges:
        adj_json.setdefault(parent, []).append({"to": child, "wire": loop.get('loopWireway', '?'), "id": loop.get('id', '')})

    svg_elems = []
    # 边
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
        wire = loop.get('loopWireway', '?')
        upd = updates.get(child)
        if upd:
            label_text = f"{_svg_escape(upd['old_wire'])} (建议:{upd['calc_value']:.2f})"
            color, lw = "#e74c3c", 2.5
        else:
            label_text = _svg_escape(wire)
            color, lw = "#95a5a6", 1.5
        eid = f"edge_{parent}__{child}"
        svg_elems.append(f'<path id="{eid}" class="edge" d="{path}" stroke="{color}" stroke-width="{lw}" '
                         f'fill="none" data-from="{_svg_escape(parent)}" data-to="{_svg_escape(child)}"/>')
        lx, ly = mid_x, (y1 + y2) / 2
        lw_px = len(label_text) * 5.5 + 8
        svg_elems.append(f'<rect class="edge-label-bg" x="{lx-lw_px/2:.1f}" y="{ly-8:.1f}" width="{lw_px:.1f}" height="16" '
                         f'fill="white" opacity="0.88" rx="3" pointer-events="none"/>')
        svg_elems.append(f'<text class="edge-label" x="{lx:.1f}" y="{ly+4:.1f}" text-anchor="middle" '
                         f'font-size="10" fill="{color}" font-weight="{"bold" if upd else "normal"}" pointer-events="none">'
                         f'{label_text}</text>')

    # 节点
    for node_name, (x, y) in norm.items():
        typ = app_type_map.get(node_name, '?')
        color = TYPE_COLOR.get(typ, '#bdc3c7')
        name_esc = _svg_escape(node_name)
        type_esc = _svg_escape(typ)
        rect_w = max(len(node_name) * 9, len(typ) * 7, 70) + 20
        rect_h = 34
        upd = updates.get(node_name)
        stroke = '#e74c3c' if upd else '#2c3e50'
        stroke_w = 2.5 if upd else 1.5
        nid = f"node_{node_name}"
        svg_elems.append(f'<g id="{nid}" class="node" data-name="{name_esc}" data-type="{type_esc}" '
                         f'style="cursor:pointer;">')
        svg_elems.append(f'<rect x="{x-rect_w/2:.1f}" y="{y-rect_h/2:.1f}" width="{rect_w:.1f}" height="{rect_h}" '
                         f'rx="6" fill="{color}" stroke="{stroke}" stroke-width="{stroke_w}"/>')
        svg_elems.append(f'<text x="{x:.1f}" y="{y-3:.1f}" text-anchor="middle" font-size="11" '
                         f'fill="white" font-weight="bold" pointer-events="none">{name_esc}</text>')
        svg_elems.append(f'<text x="{x:.1f}" y="{y+10:.1f}" text-anchor="middle" font-size="9" '
                         f'fill="white" opacity="0.85" pointer-events="none">{type_esc}</text>')
        svg_elems.append('</g>')

    svg_content = '\n  '.join(svg_elems)
    update_count = len(updates) if updates else 0
    title_suffix = f' | {update_count} 个父回路选型被更新(红色)' if updates else ''

    import json as _json
    adj_json_str = _json.dumps(adj_json, ensure_ascii=False)

    html = f'''<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>配电驱动优化树状结构</title>
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
<div id="title">配电驱动优化树状结构 (类型{optimize_type}) - 节点间标注回路导线选型{title_suffix}</div>
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

function updateVB() {{
  svgEl.setAttribute('viewBox', vb.x + ' ' + vb.y + ' ' + vb.w + ' ' + vb.h);
}}
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

// 点击节点高亮下游
function bfsDownstream(start) {{
  const visited = new Set([start]);
  const queue = [start];
  const edges = [];
  while (queue.length) {{
    const node = queue.shift();
    const neighbors = adjData[node] || [];
    for (const n of neighbors) {{
      edges.push({{from: node, to: n.to, wire: n.wire}});
      if (!visited.has(n.to)) {{
        visited.add(n.to);
        queue.push(n.to);
      }}
    }}
  }}
  return {{ nodes: visited, edges: edges }};
}}

function highlightDownstream(nodeName) {{
  const result = bfsDownstream(nodeName);
  // dim 所有节点和边
  document.querySelectorAll('.node').forEach(g => g.classList.add('dim'));
  document.querySelectorAll('.edge, .edge-label-bg, .edge-label').forEach(e => e.classList.add('dim'));
  // 高亮
  result.nodes.forEach(n => {{
    const g = document.getElementById('node_' + n);
    if (g) {{ g.classList.remove('dim'); g.classList.add('highlight'); }}
  }});
  result.edges.forEach(e => {{
    const path = document.getElementById('edge_' + e.from + '__' + e.to);
    if (path) path.classList.remove('dim');
  }});
  // 信息面板
  const info = document.getElementById('info');
  const childNodes = [...result.nodes].filter(n => n !== nodeName);
  let html = '<h4>' + nodeName + ' 下游回路</h4>';
  html += '<div class="row">下游节点数: <b>' + childNodes.length + '</b></div>';
  html += '<div class="row">下游回路数: <b>' + result.edges.length + '</b></div>';
  html += '<hr style="margin:6px 0;border:none;border-top:1px solid #ecf0f1;">';
  // 按回路分组显示
  const grouped = {{}};
  result.edges.forEach(e => {{
    const k = e.from + ' -> ' + e.to;
    grouped[k] = e.wire;
  }});
  Object.keys(grouped).sort().forEach(k => {{
    html += '<div class="row">' + k + ' <span class="wire">' + grouped[k] + '</span></div>';
  }});
  info.innerHTML = html;
  info.style.display = 'block';
}}

function resetHighlight() {{
  document.querySelectorAll('.node').forEach(g => {{
    g.classList.remove('dim'); g.classList.remove('highlight');
  }});
  document.querySelectorAll('.edge, .edge-label-bg, .edge-label').forEach(e => e.classList.remove('dim'));
  document.getElementById('info').style.display = 'none';
}}

// 节点点击事件
document.querySelectorAll('.node').forEach(g => {{
  g.addEventListener('click', function(e) {{
    if (dragMoved) return;
    const name = g.getAttribute('data-name');
    document.querySelectorAll('.node').forEach(n => n.classList.remove('highlight'));
    highlightDownstream(name);
    e.stopPropagation();
  }});
}});

// 点击空白重置
svgEl.addEventListener('click', function(e) {{
  if (dragMoved) return;
  if (e.target.tagName === 'svg' || e.target.id === 'treeGroup') resetHighlight();
}});

updateVB();
</script>
</body>
</html>'''

    out_path = output or 'wire_selection_tree.html'
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write(html)
    print(f'HTML 已生成: {out_path}')
    print('用浏览器打开即可,支持滚轮缩放/拖拽平移')


def print_updates(updates, optimize_type):
    """在控制台打印导线选型更新过程"""
    if not updates:
        print('\n未触发导线选型更新(优化类型={}, 或无配电/控制器父回路)'.format(optimize_type))
        return
    print('\n' + '=' * 70)
    print(f'导线选型更新过程 (优化类型={optimize_type})')
    print('=' * 70)
    for node, info in updates.items():
        print(f'\n节点: {node} -> 父节点: {info["parent"]}')
        print(f'  子回路线径总和: {" + ".join(f"{g}" for _, _, g in info["children_gauges"])} = {info["sum_gauge"]:.4f}')
        for child, wire, gauge in info['children_gauges']:
            print(f'    - {child}: {wire} (线径={gauge})')
        print(f'  应用系数后计算值: {info["sum_gauge"]:.4f} * coeff = {info["calc_value"]:.4f}')
        print(f'  父回路原选型: {info["old_wire"]}')
    print('=' * 70)


def main():
    parser = argparse.ArgumentParser(description='配电驱动优化 - 回路导线选型树状结构可视化')
    parser.add_argument('--input', default=r'F:\office\idearProjects\project20251009\src\main\resources\电源分配优化日志.txt', required=False, help='算法入参 JSON 文件')
    parser.add_argument('--output', default=None, help='输出 HTML 路径(不传则默认 wire_selection_tree.html)')
    parser.add_argument('--type', default='3', choices=['3', '5'],
                        help='优化类型: 3=配电回路, 5=配电+主供电+驱动 (默认3)')
    args = parser.parse_args()

    data = load_input(args.input)
    root, tree_children, parent_loop, app_type_map, all_edges = build_tree(data)
    if root is None:
        print('未找到根节点(发电单元/储电单元),请检查 appPositions 中的 appType 字段')
        return

    print(f'根节点: {root} ({app_type_map.get(root, "?")})')
    print(f'树节点总数: {len(app_type_map)}')
    print(f'回路连接数: {len(all_edges)}')

    updates = simulate_wire_update(root, tree_children, parent_loop, app_type_map, args.type)
    print_updates(updates, args.type)

    generate_html(root, tree_children, parent_loop, app_type_map, updates, args.output, args.type, all_edges)


if __name__ == '__main__':
    main()
