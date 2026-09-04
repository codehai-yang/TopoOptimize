# -*- coding: utf-8 -*-
"""
将线束拓扑优化结果 JSON 导出为 Excel，并标出与 base 方案不同的分支。

布局：
  - 第一列：分支名称（serviceableEdges[].edgeName）
  - 之后每列：一个方案的通断状态（serviceableStatue[]，B/C/S）
  - base 方案 = initializationScheme:true 的那一列，列头用蓝色底高亮
  - 每个方案中与 base 当前分支状态不一致的单元格，用黄色底标注
  - 表格下方追加每个方案的 总成本 / 总重量 / 总长度
  - 另建一张「总成本汇总」表

用法：python export_schemes_to_excel.py [输入json] [输出xlsx]
"""
import datetime
import json
import io
import sys

import pandas as pd
from openpyxl.styles import PatternFill
from openpyxl.utils import get_column_letter

# 颜色
DIFF_FILL = PatternFill(fill_type='solid', start_color='FFD966', end_color='FFD966')  # 黄 - 与base不同
BASE_FILL = PatternFill(fill_type='solid', start_color='4F81BD', end_color='4F81BD')  # 蓝 - base列头
HEADER_FILL = PatternFill(fill_type='solid', start_color='D9D9D9', end_color='D9D9D9')  # 灰 - 普通列头


def main(input_path, output_path):
    with io.open(input_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    if not isinstance(data, list) or len(data) == 0:
        raise ValueError("根节点必须是非空 list（多个方案）")

    # 定位 base 方案（initializationScheme == true）
    base_idx = next((i for i, s in enumerate(data)
                     if s.get('initializationScheme') is True), None)
    if base_idx is None:
        print("[WARN] 未找到 initializationScheme=true 的 base 方案，将不做差异标色")
    base_statue = data[base_idx]['serviceableStatue'] if base_idx is not None else None

    # 分支名称：以第一个方案的分支顺序为准（已验证各方案顺序一致）
    branch_names = [e.get('edgeName') or e.get('id') or '?'
                    for e in data[0]['serviceableEdges']]

    headers = ['分支名称'] + ['方案%d' % (i + 1) for i in range(len(data))]
    status_cols = {('方案%d' % (i + 1)): s['serviceableStatue']
                   for i, s in enumerate(data)}

    df = pd.DataFrame(status_cols)
    df.insert(0, '分支名称', branch_names)

    cost_rows = []
    for i, s in enumerate(data, start=1):
        c = s['成本']
        cost_rows.append({'方案': '方案%d' % i,
                          '总成本(元)': c.get('总成本'),
                          '总重量(克)': c.get('总重量'),
                          '总长度(mm)': c.get('总长度')})

    with pd.ExcelWriter(output_path, engine='openpyxl') as writer:
        df.to_excel(writer, sheet_name='方案通断状态', index=False, startrow=0)
        pd.DataFrame(cost_rows).to_excel(writer, sheet_name='总成本汇总', index=False)

        # ----- openpyxl 样式 -----
        ws = writer.book['方案通断状态']
        n_rows = df.shape[0]          # 数据行数 = 分支数
        n_cols = len(headers)         # 列数 = 1 + 方案数

        # 列头样式：全部灰底；base 列头蓝底
        for j in range(1, n_cols + 1):
            cell = ws.cell(row=1, column=j)
            if base_idx is not None and j == base_idx + 2:  # +2: A是分支名, 方案1从B开始
                cell.fill = BASE_FILL
                cell.value = headers[j - 1] + ' (base)'
            else:
                cell.fill = HEADER_FILL

        # 数据行：与 base 不同的状态单元格标黄
        if base_statue is not None:
            for r in range(n_rows):
                base_val = base_statue[r]
                for j in range(2, n_cols + 1):        # 列B..：方案列 (A是分支名)
                    cell = ws.cell(row=r + 2, column=j)
                    if cell.value is not None and str(cell.value) != str(base_val):
                        cell.fill = DIFF_FILL

        # 下方成本指标块：数据行下方空一行后写入 成本/重量/长度
        s0 = df.shape[0] + 3
        ws.cell(row=s0, column=1, value='总成本(元)')
        ws.cell(row=s0 + 1, column=1, value='总重量(克)')
        ws.cell(row=s0 + 2, column=1, value='总长度(mm)')
        for i, s in enumerate(data, start=1):
            c = s['成本']
            ws.cell(row=s0, column=i + 1, value=c.get('总成本'))
            ws.cell(row=s0 + 1, column=i + 1, value=c.get('总重量'))
            ws.cell(row=s0 + 2, column=i + 1, value=c.get('总长度'))

        # 自适应列宽（仅分支名列放宽）
        ws.column_dimensions['A'].width = 30

    print('OK 已导出: %s' % output_path)
    print('方案数: %d, 分支数: %d, base 方案: 方案%d' % (len(data), len(branch_names), base_idx + 1 if base_idx is not None else -1))


if __name__ == '__main__':
    input_path = (sys.argv[1] if len(sys.argv) > 1
                  else r'f:\office\idearProjects\project20251009\src\main\resources\测试新遗传算法2_formatted.json')
    output_path = (sys.argv[2] if len(sys.argv) > 2
                   else r'f:\office\idearProjects\project20251009\src\main\resources\测试新遗传算法2.xlsx')
    main(input_path, output_path)