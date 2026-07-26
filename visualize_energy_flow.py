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
  python visualize_energy_flow.py <整车json> [拓扑文件] --circuit-idx 1
  python visualize_energy_flow.py vehicle.json --topo BS4EM项目json优化设置.txt --circuit-idx 3
"""
import json
import os
import argparse

import networkx as nx
import matplotlib.pyplot as plt
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


# --------------------------------------------------------------------------- #
# 读取：整车计算后的 json(circuitInfo[])
# --------------------------------------------------------------------------- #
def load_vehicle(vehicle_path):
    with open(vehicle_path, 'r', encoding='utf-8') as f:
        return json.load(f)


def get_circuit(vehicle, idx):
    """从 circuitInfo 列表里取第 idx 根回路(1-based)"""
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
    if idx < 1 or idx > len(arr):
        raise ValueError(f'circuit-idx={idx} 越界，共 {len(arr)} 根回路(1-based)')
    return arr[idx - 1], len(arr)


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
         save_path):
    fig, ax = plt.subplots(figsize=(24, 18))

    # 整张拓扑(浅灰底)
    nx.draw_networkx_edges(G, pos, ax=ax, edge_color='#cccccc',
                           width=0.5, alpha=0.5)
    nx.draw_networkx_nodes(G, pos, ax=ax, node_size=3,
                           node_color='#dddddd', edgecolors='none')

    # 回路自身走向(紫色虚线)
    if circuit_edges:
        nx.draw_networkx_edges(G, pos, edgelist=circuit_edges, ax=ax,
                               edge_color='purple', width=2,
                               style='dashed', alpha=0.85)

    # 不绕路(蓝色)
    if no_detour_edges:
        nx.draw_networkx_edges(G, pos, edgelist=no_detour_edges, ax=ax,
                               edge_color='blue', width=3.5, alpha=0.9)

    # 绕路 / 能量流(红色)
    if detour_edges:
        nx.draw_networkx_edges(G, pos, edgelist=detour_edges, ax=ax,
                               edge_color='red', width=3.5, alpha=0.95)

    # 标注两条路径上的位置名(小字)
    for name in set([n for e in (detour_edges + no_detour_edges) for n in e]):
        if name in pos:
            x, y = pos[name]
            ax.annotate(name, (x, y), fontsize=6, color='#333333',
                        xytext=(2, 2), textcoords='offset points')

    # 起点 / 终点 标记
    start = circuit.get('起点位置名称')
    end = circuit.get('终点位置名称') or circuit.get('焊点位置名称')
    if start and start in pos:
        x, y = pos[start]
        ax.scatter([x], [y], marker='^', s=160, c='green', zorder=5,
                   edgecolors='black')
        ax.annotate(f"起点:{start}", (x, y), fontsize=9, weight='bold',
                    color='green', xytext=(4, 4), textcoords='offset points')
    if end and end in pos:
        x, y = pos[end]
        ax.scatter([x], [y], marker='s', s=140, c='red', zorder=5,
                   edgecolors='black')
        ax.annotate(f"终点:{end}", (x, y), fontsize=9, weight='bold',
                    color='red', xytext=(4, 4), textcoords='offset points')

    # 图例
    legend_handles = [
        Line2D([0], [0], color='red', lw=3.5, label='能量流(绕路)'),
        Line2D([0], [0], color='blue', lw=3.5, label='不绕路'),
        Line2D([0], [0], color='purple', lw=2, linestyle='--',
                label='回路自身走向'),
        Line2D([0], [0], color='#cccccc', lw=1, label='整图拓扑'),
        Line2D([0], [0], marker='^', color='green', lw=0,
                label='起点', markersize=10),
        Line2D([0], [0], marker='s', color='red', lw=0,
                label='终点', markersize=10),
    ]
    ax.legend(handles=legend_handles, loc='upper right', fontsize=11)

    title = (f"回路能量流路径可视化\n"
             f"起点={circuit.get('起点用电器名称')} → "
             f"终点={circuit.get('终点用电器名称')} | "
             f"信号名={circuit.get('回路信号名')}")
    ax.set_title(title, fontsize=13, weight='bold')
    ax.set_aspect('equal')
    plt.tight_layout()
    plt.savefig(save_path, dpi=200, bbox_inches='tight')
    print(f"[OK] 已保存: {save_path}")
    plt.show()


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main():
    parser = argparse.ArgumentParser(description='整车回路能量流路径可视化')
    parser.add_argument('vehicle_json', nargs=r'F:\office\idearProjects\project20251009\src\main\resources\output.txt',
                        help='整车计算后的 json(根含 circuitInfo[])',
                        default=r'F:\office\idearProjects\project20251009\src\main\resources\output.txt')
    parser.add_argument('topo', nargs=r'F:\office\idearProjects\project20251009\src\main\resources\能量流json日志.txt',
                        default=r'F:\office\idearProjects\project20251009\src\main\resources\能量流json日志.txt',
                        help='拓扑文件(含 edges 与坐标)，不传则用默认路径')
    parser.add_argument('--circuit-idx', type=int, default=1,
                        help='绘制第几根回路(1-based，默认 1)')
    parser.add_argument('--save', default='energy_flow.png',
                        help='输出图片路径，默认 energy_flow.png')
    args = parser.parse_args()

    print(f"[INFO] 读整车 json: {args.vehicle_json}")
    vehicle = load_vehicle(args.vehicle_json)
    circuit, total = get_circuit(vehicle, args.circuit_idx)
    print(f"[INFO] 选中第 {args.circuit_idx}/{total} 根回路")
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

    # 三条路径
    detour_pts = circuit.get('能量流途径分支点名称') or []
    no_detour_pts = circuit.get('能量流不绕路途径分支点名称') or []
    circuit_pts = circuit.get('回路途径分支点') or []

    detour_edges, d_missing = expand_path(G, detour_pts)
    no_detour_edges, n_missing = expand_path(G, no_detour_pts)
    circuit_edges, c_missing = expand_path(G, circuit_pts)

    print(f"[INFO] 能量流(绕路) 位置数={len(detour_pts)}, 展开边={len(detour_edges)}"
          + (f", 缺失={d_missing}" if d_missing else ""))
    print(f"[INFO] 不绕路 位置数={len(no_detour_pts)}, 展开边={len(no_detour_edges)}"
          + (f", 缺失={n_missing}" if n_missing else ""))
    print(f"[INFO] 回路自身 位置数={len(circuit_pts)}, 展开边={len(circuit_edges)}"
          + (f", 缺失={c_missing}" if c_missing else ""))

    if not detour_edges and not no_detour_edges and not circuit_edges:
        print("[WARN] 该回路在整图中没有任何可画的位置路径，"
              "请确认拓扑文件与整车 json 是同一项目。")

    draw(G, pos, circuit, detour_edges, no_detour_edges, circuit_edges,
         args.save)


if __name__ == '__main__':
    main()
