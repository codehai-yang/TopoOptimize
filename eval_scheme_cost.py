# -*- coding: utf-8 -*-
"""
方案成本对比：读取线束拓扑优化结果 JSON，用 GINE 模型预测每个方案的成本，
与 JSON 里的真实成本对比，输出 Excel。

用法：
    python eval_scheme_cost.py
    python eval_scheme_cost.py --json <结果json> --model <权重.pt> --out <输出.xlsx>
    python eval_scheme_cost.py --limit 3          # 只跑前 3 个方案，调试用

特征口径（与 Java side HarnessPackOpti.utils.Normalize#normalizeData 保持一致）：
    节点 = 各分支的起点/终点（去重，顺序不影响结果：GINE + global_add_pool 对节点排列等变）
    x[:, :N]  回路单价矩阵：对每条回路，matrix[起点位置][终点位置] += 导线单价
    x[:, N]   节点湿区成本：对湿区回路（回路湿区成本加成 > 0），wet[起点位置] += 导线单价
    edge_attr [通断one-hot(3), 分支长度]，B=[0,0,0] C=[0,1,0] S=[0,0,1]
    分支长度  = length ?: referenceLength ?: (C/S -> 200, B -> 0)
    之后按 normalization_params.json 做统计标准化，模型输出再反标准化回元。
"""
import argparse
import json
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn
from torch_geometric.nn import GINEConv, global_add_pool

# ============================================================
# 配置
# ============================================================
EDGE_FEAT_DIM = 4
NODE_FEAT_DIM = 200
HIDDEN_DIM = 64      # 仅默认值，实际从权重推断
NUM_LAYERS = 3

ROOT = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JSON = os.path.join(ROOT, 'src', 'main', 'resources', '测试新遗传算法2.json')
SCRIPT_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'scripts')
DEFAULT_MODEL = os.path.join(SCRIPT_DIR, 'best_modelV2.pt')
DEFAULT_NORM = os.path.join(SCRIPT_DIR, 'normalization_params.json')
DEFAULT_OUT = os.path.join(ROOT, '方案成本对比.xlsx')


# ============================================================
# 模型
# ============================================================
class CostModelV2(nn.Module):
    def __init__(self, hidden_dim=HIDDEN_DIM, num_layers=NUM_LAYERS):
        super().__init__()
        self.input_proj = nn.Linear(NODE_FEAT_DIM, hidden_dim)
        self.convs = nn.ModuleList()
        self.norms = nn.ModuleList()
        for _ in range(num_layers):
            mlp = nn.Sequential(
                nn.Linear(hidden_dim, hidden_dim * 2), nn.ReLU(),
                nn.Linear(hidden_dim * 2, hidden_dim),
            )
            self.convs.append(GINEConv(mlp, edge_dim=EDGE_FEAT_DIM))
            self.norms.append(nn.LayerNorm(hidden_dim))
        half = max(hidden_dim // 2, 1)
        self.regressor = nn.Sequential(
            nn.Linear(hidden_dim, half), nn.ReLU(),
            nn.Linear(half, 1),
        )

    def forward(self, x, edge_index, edge_attr, batch=None):
        h = self.input_proj(x)
        for conv, norm in zip(self.convs, self.norms):
            h = conv(h, edge_index, edge_attr)
            h = norm(h)
            h = torch.relu(h)
        if batch is None:
            batch = torch.zeros(h.size(0), dtype=torch.long, device=h.device)
        graph_emb = global_add_pool(h, batch)
        return self.regressor(graph_emb).squeeze()


def load_model(model_path, device):
    state = torch.load(model_path, map_location=device)
    if isinstance(state, dict) and 'input_proj.weight' not in state:
        for key in ('model_state_dict', 'state_dict', 'model', 'net'):
            if key in state and isinstance(state[key], dict):
                state = state[key]
                break
    hidden_dim = int(state['input_proj.weight'].shape[0])
    layer_ids = {int(k.split('.')[1]) for k in state if k.startswith('convs.')}
    num_layers = (max(layer_ids) + 1) if layer_ids else NUM_LAYERS
    model = CostModelV2(hidden_dim=hidden_dim, num_layers=num_layers)
    model.load_state_dict(state)
    model.to(device).eval()
    print('[模型] %s  hidden=%d  layers=%d' % (os.path.basename(model_path), hidden_dim, num_layers))
    return model


# ============================================================
# 流式读取：逐个产出顶层方案对象（避免整体 json.load 吃掉几 G 内存）
# ============================================================
def iter_schemes(path):
    buf = []
    depth = 0
    instr = False
    esc = False
    started = False

    with open(path, 'r', encoding='utf-8') as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            for ch in chunk:
                if instr:
                    if started:
                        buf.append(ch)
                    if esc:
                        esc = False
                    elif ch == '\\':
                        esc = True
                    elif ch == '"':
                        instr = False
                    continue

                if ch == '"':
                    instr = True
                    if started:
                        buf.append(ch)
                    continue

                if not started:
                    if ch == '[':
                        started = True
                        depth = 1
                    continue

                if ch in '{[':
                    depth += 1
                    buf.append(ch)
                elif ch in '}]':
                    depth -= 1
                    buf.append(ch)
                    if depth == 1:
                        yield json.loads(''.join(buf))
                        del buf[:]
                    elif depth == 0:
                        return
                else:
                    if depth > 1:
                        buf.append(ch)
                    # depth == 1 时是顶层 ',' 或空白，丢弃


# ============================================================
# 特征构建
# ============================================================
def branch_length(edge):
    """与 Java getBranchLength 一致：用户长度 > 参考长度 > 状态默认值。"""
    v = edge.get('length')
    if v not in (None, ''):
        try:
            return float(v)
        except (TypeError, ValueError):
            pass
    v = edge.get('referenceLength')
    if v not in (None, ''):
        try:
            return float(v)
        except (TypeError, ValueError):
            pass
    return 200.0 if edge.get('topologyStatusCode') in ('C', 'S') else 0.0


def build_features(scheme):
    edges = scheme.get('serviceableEdges') or []
    circuits = scheme.get('circuitInfo') or []
    statues = scheme.get('serviceableStatue') or []
    if not edges:
        raise ValueError('方案缺少 serviceableEdges')

    # 节点表：各分支起点/终点，首次出现顺序
    index = {}
    for e in edges:
        for name in (e.get('startPointName'), e.get('endPointName')):
            if name is not None and name not in index:
                index[name] = len(index)
    n_nodes = len(index)
    if n_nodes > NODE_FEAT_DIM - 1:
        raise ValueError('分支点数 %d 超过节点特征容量 %d' % (n_nodes, NODE_FEAT_DIM - 1))

    # x: 回路单价矩阵 + 湿区成本列
    x = np.zeros((n_nodes, NODE_FEAT_DIM), dtype=np.float32)
    skipped = 0
    for c in circuits:
        s, t = c.get('起点位置名称'), c.get('终点位置名称')
        i, j = index.get(s), index.get(t)
        if i is None or j is None:
            skipped += 1
            continue
        try:
            price = float(c.get('导线单价') or 0.0)
        except (TypeError, ValueError):
            price = 0.0
        x[i, j] += price
        try:
            wet_add = float(c.get('回路湿区成本加成') or 0.0)
        except (TypeError, ValueError):
            wet_add = 0.0
        if wet_add > 0:
            x[i, n_nodes] += price

    # edge_index / edge_attr
    n_edges = len(edges)
    edge_index = np.zeros((2, n_edges), dtype=np.int64)
    edge_attr = np.zeros((n_edges, EDGE_FEAT_DIM), dtype=np.float32)
    onehot = {'B': (0.0, 0.0, 0.0), 'C': (0.0, 1.0, 0.0), 'S': (0.0, 0.0, 1.0)}
    for k, e in enumerate(edges):
        edge_index[0, k] = index[e['startPointName']]
        edge_index[1, k] = index[e['endPointName']]
        status = statues[k] if k < len(statues) else e.get('topologyStatusCode')
        h = onehot.get(status, (0.0, 0.0, 0.0))
        edge_attr[k, 0:3] = h
        edge_attr[k, 3] = branch_length(e)

    n_break = sum(1 for k in range(n_edges)
                  if (statues[k] if k < len(statues) else e.get('topologyStatusCode')) == 'B')
    meta = {'n_nodes': n_nodes, 'n_edges': n_edges, 'n_circuits': len(circuits),
            'circuits_skipped': skipped, 'n_break': n_break}
    return x, edge_index, edge_attr, meta


def normalize(x, edge_attr, params):
    ea = edge_attr.copy()
    mean, std = params['branch_length_mean'], params['branch_length_std']
    ea[:, 3] = (ea[:, 3] - mean) / std if std > 1e-6 else 0.0

    n = x.shape[0]
    xx = x.copy()
    pm = xx[:, :n]
    mask = pm != 0
    if mask.any() and params['price_std'] > 1e-6:
        pm[mask] = (pm[mask] - params['price_mean']) / params['price_std']
    xx[:, :n] = pm

    wc = xx[:, n]
    mask = wc != 0
    if mask.any() and params['wet_cost_std'] > 1e-6:
        wc[mask] = (wc[mask] - params['wet_cost_mean']) / params['wet_cost_std']
    xx[:, n] = wc
    return xx, ea


def predict_cost(model, x, edge_index, edge_attr, params, device):
    xt = torch.tensor(x, dtype=torch.float, device=device)
    eit = torch.tensor(edge_index, dtype=torch.long, device=device)
    eat = torch.tensor(edge_attr, dtype=torch.float, device=device)
    with torch.no_grad():
        out = model(xt, eit, eat).item()
    return out * params['total_cost_std'] + params['total_cost_mean']


# ============================================================
# Excel 输出
# ============================================================
def average_rank(values):
    """升序名次，并列取平均名次。"""
    order = np.argsort(values, kind='mergesort')
    ranks = np.empty(len(values), dtype=np.float64)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        avg = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[order[k]] = avg
        i = j + 1
    return ranks


def write_excel(rows, metrics, out_path, meta_info):
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
    from openpyxl.chart import BarChart, Reference
    from openpyxl.utils import get_column_letter

    thin = Side(style='thin', color='BFBFBF')
    border = Border(left=thin, right=thin, top=thin, bottom=thin)
    head_fill = PatternFill('solid', start_color='4472C4')
    head_font = Font(color='FFFFFF', bold=True)
    center = Alignment(horizontal='center', vertical='center')

    wb = Workbook()
    ws = wb.active
    ws.title = '方案成本对比'

    headers = ['方案序号', '是否初始方案', 'topoId', '分支数', '分支点数',
               '真实成本(元)', '预测成本(元)', '误差(元)', '绝对误差率(%)',
               '真实排名', '预测排名', '总重量(克)', '总长度(米)']
    ws.append(headers)
    for c in range(1, len(headers) + 1):
        cell = ws.cell(row=1, column=c)
        cell.fill = head_fill
        cell.font = head_font
        cell.alignment = center
        cell.border = border

    for r in rows:
        ws.append([r['no'], '是' if r['is_init'] else '', r['topo_id'], r['n_edges'], r['n_nodes'],
                   round(r['real'], 2), round(r['pred'], 2), round(r['pred'] - r['real'], 2),
                   round(r['abs_err_pct'], 2), r['rank_real'], r['rank_pred'],
                   r['weight'], r['length']])

    for row in ws.iter_rows(min_row=2, max_row=ws.max_row, max_col=len(headers)):
        for cell in row:
            cell.border = border
            cell.alignment = center
    # 列宽
    widths = [8, 12, 38, 8, 9, 14, 14, 12, 15, 10, 10, 12, 12]
    for i, w in enumerate(widths, start=1):
        ws.column_dimensions[get_column_letter(i)].width = w
    ws.freeze_panes = 'A2'

    # 真实 vs 预测 柱状图
    if len(rows) >= 2:
        chart = BarChart()
        chart.type = 'col'
        chart.title = '真实成本 vs 预测成本'
        chart.y_axis.title = '成本(元)'
        data = Reference(ws, min_col=6, max_col=7, min_row=1, max_row=ws.max_row)
        cats = Reference(ws, min_col=1, min_row=2, max_row=ws.max_row)
        chart.add_data(data, titles_from_data=True)
        chart.set_categories(cats)
        chart.height = 9
        chart.width = 18
        ws.add_chart(chart, 'M2')

    # 汇总页
    ws2 = wb.create_sheet('误差汇总')
    ws2.append(['指标', '数值'])
    for c in (1, 2):
        cell = ws2.cell(row=1, column=c)
        cell.fill = head_fill
        cell.font = head_font
        cell.alignment = center
        cell.border = border
    for k, v in metrics:
        ws2.append([k, v])
    for row in ws2.iter_rows(min_row=2, max_row=ws2.max_row, max_col=2):
        for cell in row:
            cell.border = border
    ws2.column_dimensions['A'].width = 28
    ws2.column_dimensions['B'].width = 22

    # 说明页
    ws3 = wb.create_sheet('口径说明')
    for line in meta_info:
        ws3.append([line])
    ws3.column_dimensions['A'].width = 110

    wb.save(out_path)


# ============================================================
# 主流程
# ============================================================
def main():
    ap = argparse.ArgumentParser(description='方案成本 真实值 vs 模型预测 对比')
    ap.add_argument('--json', default=DEFAULT_JSON, help='优化结果 JSON')
    ap.add_argument('--model', default=DEFAULT_MODEL, help='模型权重 .pt')
    ap.add_argument('--norm', default=DEFAULT_NORM, help='normalization_params.json')
    ap.add_argument('--out', default=DEFAULT_OUT, help='输出 xlsx')
    ap.add_argument('--limit', type=int, default=0, help='只处理前 N 个方案')
    args = ap.parse_args()

    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    for p in (args.json, args.model, args.norm):
        if not os.path.isfile(p):
            sys.exit('文件不存在: %s' % p)

    with open(args.norm, 'r', encoding='utf-8') as f:
        params = json.load(f)
    model = load_model(args.model, device)

    print('[读取] %s' % args.json)
    t0 = time.time()
    rows = []
    for i, scheme in enumerate(iter_schemes(args.json), start=1):
        if args.limit and i > args.limit:
            break
        try:
            x, edge_index, edge_attr, meta = build_features(scheme)
            xx, ea = normalize(x, edge_attr, params)
            pred = predict_cost(model, xx, edge_index, ea, params, device)
            cost = scheme.get('成本') or {}
            real = float(cost.get('总成本') or 0.0)
            rows.append({
                'no': i,
                'is_init': bool(scheme.get('initializationScheme')),
                'topo_id': scheme.get('topoId') or '',
                'n_edges': meta['n_edges'],
                'n_nodes': meta['n_nodes'],
                'real': real,
                'pred': pred,
                'abs_err_pct': abs(pred - real) / real * 100 if real else 0.0,
                'weight': cost.get('总重量'),
                'length': cost.get('总长度'),
            })
            print('  方案%-3d 真实 %10.2f   预测 %10.2f   误差 %+9.2f (%6.2f%%)  分支%d 节点%d'
                  % (i, real, pred, pred - real,
                     (pred - real) / real * 100 if real else 0.0,
                     meta['n_edges'], meta['n_nodes']))
        except Exception as e:
            print('  方案%-3d 处理失败: %s' % (i, e))

    if not rows:
        sys.exit('没有成功处理任何方案')

    real = np.array([r['real'] for r in rows], dtype=np.float64)
    pred = np.array([r['pred'] for r in rows], dtype=np.float64)
    diff = pred - real
    abs_diff = np.abs(diff)
    pct = abs_diff / np.where(real == 0, np.nan, real) * 100
    mae = float(abs_diff.mean())
    rmse = float(np.sqrt((diff ** 2).mean()))
    mape = float(np.nanmean(pct))
    corr = float(np.corrcoef(real, pred)[0, 1]) if len(rows) > 1 else float('nan')
    bias = float(diff.mean())

    # 排名（成本越低越好，升序；并列取平均名次），用于判断模型能否用于方案选优
    rank_real = average_rank(real)
    rank_pred = average_rank(pred)
    for r, a, b in zip(rows, rank_real, rank_pred):
        r['rank_real'] = a
        r['rank_pred'] = b
    spearman = (float(np.corrcoef(rank_real, rank_pred)[0, 1])
                if len(rows) > 1 else float('nan'))
    top1_hit = int(np.argmin(real) == np.argmin(pred)) if len(rows) else 0

    metrics = [
        ('方案数', len(rows)),
        ('平均绝对误差 MAE(元)', round(mae, 2)),
        ('均方根误差 RMSE(元)', round(rmse, 2)),
        ('平均绝对误差率 MAPE(%)', round(mape, 2)),
        ('平均偏差 bias(元)  正=高估', round(bias, 2)),
        ('最大绝对误差(元)', round(float(abs_diff.max()), 2)),
        ('真实成本区间(元)', '%.2f ~ %.2f' % (real.min(), real.max())),
        ('预测成本区间(元)', '%.2f ~ %.2f' % (pred.min(), pred.max())),
        ('真实-预测 相关系数', round(corr, 4) if corr == corr else 'N/A'),
        ('秩相关 Spearman(排名一致性)', round(spearman, 4) if spearman == spearman else 'N/A'),
        ('真实最优方案是否被预测选中', '是' if top1_hit else '否'),
        ('模型权重', os.path.basename(args.model)),
        ('归一化参数', os.path.basename(args.norm)),
    ]

    meta_info = [
        '数据来源: %s' % args.json,
        '模型权重: %s' % args.model,
        '归一化参数: %s' % args.norm,
        '',
        '特征构建口径（对齐 Java HarnessPackOpti.utils.Normalize#normalizeData）：',
        '1. 节点 = 所有分支的起点/终点名称去重，共 N 个；GINE + global_add_pool 对节点排列等变，顺序不影响结果。',
        '2. 节点特征 x[:, 0:N] = 回路单价矩阵：某条回路的起点位置 i、终点位置 j 时，x[i][j] += 该回路导线单价。',
        '3. 节点特征 x[:, N] = 湿区成本：回路的回路湿区成本加成 > 0 视为湿区回路，x[起点位置][N] += 导线单价。',
        '4. 边特征 = [通断 one-hot(3), 分支长度]；B=[0,0,0]、C=[0,1,0]、S=[0,0,1]。',
        '5. 分支长度取值：length（用户确认）> referenceLength（参考）> C/S 默认 200、B 默认 0。',
        '6. 真实成本取自每个方案对象的 成本.总成本（由项目成本计算得出，非模型输出）。',
        '7. 排名按成本升序（成本越低越好），并列取平均名次；秩相关越接近 1，说明模型给出的方案优劣顺序越可信。',
        '',
        '注意：本脚本是离线复算。若 Java 端 elecFixedLocationLibrary 中的导线单价与 JSON 的 导线单价 字段不一致，',
        '或湿区判定口径（pointList.waterParam）与 回路湿区成本加成 > 0 不完全等价，预测值会有系统性偏移。',
    ]

    write_excel(rows, metrics, args.out, meta_info)
    print('\n[完成] 用时 %.1fs' % (time.time() - t0))
    print('  MAE=%.2f 元   MAPE=%.2f%%   相关系数=%.4f   秩相关=%.4f   平均偏差=%+.2f 元'
          % (mae, mape, corr if corr == corr else float('nan'),
             spearman if spearman == spearman else float('nan'), bias))
    print('  输出: %s' % args.out)


if __name__ == '__main__':
    main()
