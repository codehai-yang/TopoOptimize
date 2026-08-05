# -*- coding: utf-8 -*-
"""
整车回路「能量流路径」可视化工具

读取：
  1) 拓扑文件(含 edges 与坐标) —— 画整张线束拓扑图(浅灰底)
  2) 整车计算后的 json(根含 circuitInfo[]) —— 取其中某一根回路

把选中的那一圈回路的：
  - 能量流途径分支点名称(绕路)        用 红色 高亮
  - 能量流不绕路途径分支点名称(不绕路) 用 蓝色 高亮
  - 回路自身途径分支点(回路走向)        用 紫色虚线

起点位置 = 绿三角, 终点位置 = 红方块, 并标注名称。
两条路径(位置名序列)会沿整图在 networkx 上逐段求最短路径后展开成真实连线，
避免直接用直线乱飞。字段为 null 时给出提示、不影响出图。

用法：
  python visualize_energy_flow.py --circuit-idx 3                # 按索引
  python visualize_energy_flow.py --circuit-number 149726         # 按回路编号
  python visualize_energy_flow.py --vehicle-json vehicle.json  \
    --topo BS4EM项目json优化设置.txt --circuit-number 149726
  (不传 --vehicle-json / --topo 时用脚本里写的默认路径)
  (--circuit-idx 和 --circuit-number 二选一)
"""
import json
import os
import argparse

import networkx as nx
import matplotlib.pyplot as plt
# 中文显示：指定中文字体，避免标题/标签/图例变成方框
plt.rcParams['font.sans-serif'] = ['SimHei', 'Microsoft YaHei', 'SimSun', 'Arial Unicode MS']
plt.rcParams['axes.unicode_minus'] = False
from matplotlib.lines import Line2D


# --------------------------------------------------------------------------- #
# 读取：拓扑文件(edges + 坐标)
# --------------------------------------------------------------------------- #
def load_topo(topo_path):
    """读取含 edges 的拓扑文件(支持标准 json / txt 末尾被污染的 raw_decode)"""
    with open(topo_path, 'r', encoding='utf-8') as f:
        content = f.read()
    try:
        obj = json.loads(content)
    except Exception:
        decoder = json.JSONDecoder()
        obj, _ = decoder.raw_decode(content)
    return obj.get('edges', [])


def build_graph(edges):
    """用 edges 建无向图并取每个点的位置坐标 (x, y)"""
    G = nx.Graph()
    pos = {}
    for e in edges:
        sp = e.get('startPointName', e.get('startPoint'))
        ep = e.get('endPointName', e.get('endPoint'))
        if not sp or not ep:
            continue
        G.add_edge(sp, ep)
        if sp not in pos:
            pos[sp] = (to_float(e.get('startXCoordinate')),
                       to_float(e.get('startYCoordinate')))
        if ep not in pos:
            pos[ep] = (to_float(e.get('endXCoordinate')),
                       to_float(e.get('endYCoordinate')))
    return G, pos


def to_float(v, default=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def build_pos_app_map(vehicle):
    """遍历整车 json 中所有回路，建立 位置名称 -> 用电器名称 的映射。

    用电器只挂在本回路的起点/终点位置上，中间分支点通常无对应用电器。
    返回 dict: {位置名: 用电器名}（同一位置多个用电器时用逗号拼接）。
    """
    pos_map = {}
    arr = _get_circuit_list(vehicle) if isinstance(vehicle, dict) else vehicle
    for c in arr:
        app = c.get('起点用电器名称')
        pos_name = c.get('起点位置名称')
        if app and pos_name:
            _add_pos_app(pos_map, pos_name, app)
        app = c.get('终点用电器名称')
        pos_name = c.get('终点位置名称') or c.get('焊点位置名称')
        if app and pos_name:
            _add_pos_app(pos_map, pos_name, app)
    return pos_map


def _add_pos_app(pos_map, pos_name, app):
    """把 位置->用电器 加入映射；同位置多用电器用逗号拼接去重"""
    existing = pos_map.get(pos_name)
    if existing is None:
        pos_map[pos_name] = app
    elif existing != app and app not in existing.split(','):
        pos_map[pos_name] = existing + ',' + app


# --------------------------------------------------------------------------- #
# 读取：整车计算后的 json(circuitInfo[])
# --------------------------------------------------------------------------- #
def load_vehicle(vehicle_path):
    with open(vehicle_path, 'r', encoding='utf-8') as f:
        return json.load(f)


def get_circuit_by_number(vehicle, circuit_number):
    """按回路编号查找，返回 (circuit, 所在索引1-based, 总回路数)"""
    arr = _get_circuit_list(vehicle)
    for i, c in enumerate(arr, 1):
        if str(c.get('回路编号')) == str(circuit_number):
            return c, i, len(arr)
    raise ValueError(f'未找到回路编号={circuit_number} 的回路')


def get_circuit(vehicle, idx):
    """从 circuitInfo 列表里取第 idx 根回路(1-based)"""
    arr = _get_circuit_list(vehicle)
    if idx < 1 or idx > len(arr):
        raise ValueError(f'circuit-idx={idx} 越界，共 {len(arr)} 根回路(1-based)')
    return arr[idx - 1], idx, len(arr)


def _get_circuit_list(vehicle):
    """提取 circuitInfo 列表（兼容多种 json 结构）"""
    arr = vehicle.get('circuitInfo')
    if arr is None:
        if isinstance(vehicle, list):
            arr = vehicle
        else:
            for v in vehicle.values():
                if isinstance(v, list) and v and isinstance(v[0], dict) \
                        and '回路id' in v[0]:
                    arr = v
                    break
    if not arr:
        raise ValueError('未找到 circuitInfo 回路列表')
    return arr


# --------------------------------------------------------------------------- #
#  用分支 id 列表直接从拓扑 edges 中找到对应的 (起点名, 终点名) 边序列
# --------------------------------------------------------------------------- #
def build_edges_from_ids(edge_id_list, topo_edges):
    """根据分支 id 列表返回 (edges_list, missing_ids)"""
    # 建立 分支id → (起点名, 终点名) 映射
    id_to_edge = {}
    for e in topo_edges:
        eid = (e.get('分支id编号') or e.get('id') or e.get('分支id')
               or e.get('edgeId'))
        sp = e.get('startPointName', e.get('startPoint'))
        ep = e.get('endPointName', e.get('endPoint'))
        if eid and sp and ep:
            id_to_edge[str(eid)] = (sp, ep)

    edges_out, missing = [], []
    for eid in edge_id_list:
        eid = str(eid).strip()
        if eid in id_to_edge:
            edges_out.append(id_to_edge[eid])
        else:
            missing.append(eid)
    return edges_out, missing


def order_path_nodes(edges_list):
    """把无向边对序列按"首尾相接"还原成有序节点序列。

    边 id 列表在后端是按 源→消费端 方向存储的，因此还原出的节点序列
    第一个节点即能量流源头(发电/储电单元端)。
    edges_list: [(u,v), ...]（来自 build_edges_from_ids，无向）
    返回有序节点列表，无法定向时返回 None。
    """
    if not edges_list:
        return None
    # 记录每个节点的邻接关系
    adj = {}
    for u, v in edges_list:
        adj.setdefault(u, set()).add(v)
        adj.setdefault(v, set()).add(u)
    # 找路径端点：度数为 1 的节点（一条连续路径只有两个端点）
    endpoints = [n for n, nbs in adj.items() if len(nbs) == 1]
    if len(endpoints) != 2:
        # 无法确定端点（可能是环或分支），退回用原始顺序的第一条边
        return None
    start = endpoints[0]
    # 沿邻接走出一条有序序列
    order = [start]
    visited = {start}
    cur = start
    while len(order) < len(adj):
        nxt = None
        for nb in adj[cur]:
            if nb not in visited:
                nxt = nb
                break
        if nxt is None:
            break
        visited.add(nxt)
        order.append(nxt)
        cur = nxt
    return order


# --------------------------------------------------------------------------- #
# 把一串位置名展开成真实连线边序列(在 G 中逐段最短路径)
# --------------------------------------------------------------------------- #
def expand_path(G, point_list):
    """返回 (edges_list[(u,v)...], missing[(a,b)...])"""
    edges_out, missing = [], []
    if not point_list or len(point_list) < 2:
        return edges_out, missing
    for i in range(len(point_list) - 1):
        a, b = point_list[i], point_list[i + 1]
        if a not in G or b not in G:
            missing.append((a, b))
            continue
        try:
            sub = nx.shortest_path(G, a, b)
            for j in range(len(sub) - 1):
                edges_out.append((sub[j], sub[j + 1]))
        except nx.NetworkXNoPath:
            missing.append((a, b))
    return edges_out, missing


# --------------------------------------------------------------------------- #
# 绘制
# --------------------------------------------------------------------------- #
def draw(G, pos, circuit, detour_edges, no_detour_edges, circuit_edges,
         save_path, source_node=None, pos_app_map=None):
    fig, ax = plt.subplots(figsize=(24, 18))

    # 整张拓扑(浅灰底)
    nx.draw_networkx_edges(G, pos, ax=ax, edge_color='#cccccc',
                           width=0.5, alpha=0.5)
    nx.draw_networkx_nodes(G, pos, ax=ax, node_size=3,
                           node_color='#dddddd', edgecolors='none')

    # 两套路径都画出来，用 alpha 控制显隐：
    #   绕路(红)、不绕路(蓝)。mode 控制当前显示：'detour'/'no_detour'/'all'/'none'
    if detour_edges:
        detour_coll = nx.draw_networkx_edges(G, pos, edgelist=detour_edges, ax=ax,
                                             edge_color='red', width=3.5, alpha=0.9)
    else:
        detour_coll = None
    if no_detour_edges:
        nodetour_coll = nx.draw_networkx_edges(G, pos, edgelist=no_detour_edges, ax=ax,
                                               edge_color='blue', width=3.5, alpha=0.9)
    else:
        nodetour_coll = None

    # 标注两套路径上的位置名(小字)，若该位置对应用电器则附上用电器名称
    labels = set()
    for e in (detour_edges + no_detour_edges):
        labels.update(e)
    label_arts = []
    pos_app_map = pos_app_map or {}
    for name in labels:
        if name in pos:
            x, y = pos[name]
            app = pos_app_map.get(name)
            text = f"{name} [{app}]" if app else name
            t = ax.annotate(text, (x, y), fontsize=6, color='#333333',
                            xytext=(2, 2), textcoords='offset points')
            label_arts.append(t)

    # 当前显示模式
    state = {'mode': 'no_detour'}   # 默认只显示不绕路

    def _apply():
        det_alpha = 0.9 if (state['mode'] in ('detour', 'all')) else 0.0
        nd_alpha = 0.9 if (state['mode'] in ('no_detour', 'all')) else 0.0
        if detour_coll is not None:
            detour_coll.set_alpha(det_alpha)
        if nodetour_coll is not None:
            nodetour_coll.set_alpha(nd_alpha)
        fig.canvas.draw_idle()

    def _update_title():
        mode_cn = {'detour': '绕路(红)', 'no_detour': '不绕路(蓝)',
                   'all': '全部', 'none': '全部隐藏'}
        ax.set_title(
            f"回路能量流路径可视化 [{mode_cn[state['mode']]}]\n"
            f"起点={circuit.get('起点用电器名称')} → "
            f"终点={circuit.get('终点用电器名称')} | "
            f"信号名={circuit.get('回路信号名')}\n"
            "按键: D=绕路  N=不绕路  A=全部  H=隐藏",
            fontsize=11, weight='bold')
        fig.canvas.draw_idle()

    def on_key(event):
        k = (event.key or '').lower()
        if k == 'd':
            state['mode'] = 'detour'
        elif k == 'n':
            state['mode'] = 'no_detour'
        elif k == 'a':
            state['mode'] = 'all'
        elif k == 'h':
            state['mode'] = 'none'
        else:
            return
        _apply()
        _update_title()

    fig.canvas.mpl_connect('key_press_event', on_key)

    # 图例：点击"绕路/不绕路"图例可切换显示
    legend_handles = [
        Line2D([0], [0], color='red', lw=3.5, label='绕路(点此显示)'),
        Line2D([0], [0], color='blue', lw=3.5, label='不绕路(点此显示)'),
        Line2D([0], [0], color='#cccccc', lw=1, label='整图拓扑'),
    ]
    leg = ax.legend(handles=legend_handles, loc='upper right', fontsize=11)
    for hp in leg.get_lines():
        hp.set_picker(True)          # 允许点击图例句柄
        hp.set_pickradius(8)

    def on_pick(event):
        lbl = event.artist.get_label()
        if '绕路' in lbl:
            state['mode'] = 'detour'
        elif '不绕路' in lbl:
            state['mode'] = 'no_detour'
        _apply()
        _update_title()

    fig.canvas.mpl_connect('pick_event', on_pick)

    _update_title()
    ax.set_aspect('equal')
    plt.tight_layout()
    plt.savefig(save_path, dpi=200, bbox_inches='tight')
    print(f"[OK] 已保存: {save_path}")
    print(f"[INFO] 绕路边数={len(detour_edges)}, 不绕路边数={len(no_detour_edges)}")
    print(f"[INFO] 交互: 按 D=绕路 / N=不绕路 / A=全部 / H=隐藏，"
          f"或点击图例切换")
    plt.show()


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main():
    parser = argparse.ArgumentParser(description='整车回路能量流路径可视化')
    parser.add_argument('--vehicle-json',
                        default=r'F:\office\idearProjects\project20251009\src\main\resources\output.txt',
                        help='整车计算后的 json(根含 circuitInfo[])，不传用默认路径')
    parser.add_argument('--topo',
                        default=r'F:\office\idearProjects\project20251009\src\main\resources\能量流json日志.txt',
                        help='拓扑文件(含 edges 与坐标)，不传用默认路径')
    parser.add_argument('--circuit-idx', type=int, default=None,
                        help='绘制第几根回路(1-based)')
    parser.add_argument('--circuit-number', type=str, default=149945,
                        help='按回路编号查找(如 "149726")，与 --circuit-idx 二选一')
    parser.add_argument('--save', default='energy_flow.png',
                        help='输出图片路径，默认 energy_flow.png')
    args = parser.parse_args()

    if args.circuit_number is None and args.circuit_idx is None:
        parser.error('请提供 --circuit-idx 或 --circuit-number')

    print(f"[INFO] 读整车 json: {args.vehicle_json}")
    vehicle = load_vehicle(args.vehicle_json)

    if args.circuit_number is not None:
        circuit, pos_idx, total = get_circuit_by_number(vehicle, args.circuit_number)
        print(f"[INFO] 通过编号 {args.circuit_number} 找到回路 (第 {pos_idx}/{total} 根)")
    else:
        circuit, pos_idx, total = get_circuit(vehicle, args.circuit_idx)
        print(f"[INFO] 选中第 {pos_idx}/{total} 根回路")
    print(f"       起点用电器={circuit.get('起点用电器名称')} "
          f"({circuit.get('起点用电器类型')}) @ {circuit.get('起点位置名称')}")
    print(f"       终点用电器={circuit.get('终点用电器名称')} "
          f"({circuit.get('终点用电器类型')}) @ "
          f"{circuit.get('终点位置名称') or circuit.get('焊点位置名称')}")
    print(f"       回路信号名={circuit.get('回路信号名')}")

    print(f"[INFO] 读拓扑: {args.topo}")
    edges = load_topo(args.topo)
    G, pos = build_graph(edges)
    print(f"[INFO] 拓扑: {G.number_of_nodes()} 个点, {G.number_of_edges()} 条边")

    # 位置 -> 用电器名称 映射（用于标注路径点后附上用电器名）
    pos_app_map = build_pos_app_map(vehicle)

    # 三条路径
    # 能量流绕路 / 不绕路 → 用分支 id 直接找边
    detour_ids = circuit.get('能量流途径分支id') or []
    no_detour_ids = circuit.get('能量流不绕路途径分支id') or []
    # 回路自身 → 仍用位置名展开
    circuit_pts = circuit.get('回路途径分支点') or []

    detour_edges, d_missing = build_edges_from_ids(detour_ids, edges)
    no_detour_edges, n_missing = build_edges_from_ids(no_detour_ids, edges)
    circuit_edges, c_missing = expand_path(G, circuit_pts)

    # 推断能量流源头(发电/储电单元端)：还原绕路路径的有序节点序列，取第一个节点。
    # 若该节点不是回路自身的起点/终点位置，则是真正的源头，单独标注。
    source_node = None
    if detour_edges:
        node_seq = order_path_nodes(detour_edges)
        if node_seq:
            first = node_seq[0]
            last = node_seq[-1]
            start_pos = circuit.get('起点位置名称')
            end_pos = circuit.get('终点位置名称') or circuit.get('焊点位置名称')
            # 取不在回路起终点上的那一端作为源头
            if first != start_pos and first != end_pos:
                source_node = first
            elif last != start_pos and last != end_pos:
                source_node = last

    print(f"[INFO] 能量流(绕路) 分支id数={len(detour_ids)}, 展开边={len(detour_edges)}"
          + (f", 缺失={d_missing}" if d_missing else ""))
    if source_node:
        print(f"[INFO] 能量流源头(发电/储电单元端) = {source_node}")
    print(f"[INFO] 不绕路 分支id数={len(no_detour_ids)}, 展开边={len(no_detour_edges)}"
          + (f", 缺失={n_missing}" if n_missing else ""))
    print(f"[INFO] 回路自身 位置数={len(circuit_pts)}, 展开边={len(circuit_edges)}"
          + (f", 缺失={c_missing}" if c_missing else ""))

    if not detour_edges and not no_detour_edges and not circuit_edges:
        print("[WARN] 该回路在整图中没有任何可画的位置路径，"
              "请确认拓扑文件与整车 json 是同一项目。")

    draw(G, pos, circuit, detour_edges, no_detour_edges, circuit_edges,
         args.save, source_node=source_node, pos_app_map=pos_app_map)


if __name__ == '__main__':
    main()
