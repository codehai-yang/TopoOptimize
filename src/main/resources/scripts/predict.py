"""
独立预测脚本 —— 通过 stdin/stdout 与 Java 通信，不依赖 FastAPI。

Java 端调用方式：
    Process p = Runtime.getRuntime().exec("python predict.py");
    OutputStream out = p.getOutputStream();
    out.write(binaryData);   // Java 已有的二进制格式
    out.flush();
    out.close();
    InputStream in = p.getInputStream();
    // 读取 in 中的 JSON: {"predicted_cost": xxx, "elapsed_ms": xxx}

二进制格式（Big-Endian）:
    [4字节] num_nodes  (int32)
    [4字节] num_edges  (int32)
    [num_edges*2*4字节] edge_index (int32)
    [num_edges*4*4字节]  edge_attr  (float32)
    [num_nodes*200*4字节] x          (float32)
"""

import sys
import json
import os
import time
from typing import Optional
import numpy as np
import torch
from torch_geometric.nn import GINEConv, global_add_pool
import torch.nn as nn

# ============================================================
# 全局配置（与 Java 约定固定维度）
# ============================================================
EDGE_FEAT_DIM = 4    # 边特征维度：通断(3维) + 分支长度(1维)
NODE_FEAT_DIM = 200  # 节点特征维度
HIDDEN_DIM = 64      # GINE 隐藏层维度
NUM_LAYERS = 3       # GINE 层数

# 获取脚本所在目录（模型文件、标准化参数路径相对脚本位置）
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(SCRIPT_DIR, 'best_model.pt')
NORM_PARAMS_PATH = os.path.join(SCRIPT_DIR, 'normalization_params.json')


# ============================================================
# 模型定义（与 GINEClassifier.CostModelV2 完全一致）
# ============================================================
class CostModelV2(nn.Module):
    def __init__(self):
        super().__init__()
        self.input_proj = nn.Linear(NODE_FEAT_DIM, HIDDEN_DIM)
        self.convs = nn.ModuleList()
        self.norms = nn.ModuleList()
        for _ in range(NUM_LAYERS):
            mlp = nn.Sequential(
                nn.Linear(HIDDEN_DIM, HIDDEN_DIM * 2),
                nn.ReLU(),
                nn.Linear(HIDDEN_DIM * 2, HIDDEN_DIM),
            )
            self.convs.append(GINEConv(mlp, edge_dim=EDGE_FEAT_DIM))
            self.norms.append(nn.LayerNorm(HIDDEN_DIM))
        self.regressor = nn.Sequential(
            nn.Linear(HIDDEN_DIM, HIDDEN_DIM // 2),
            nn.ReLU(),
            nn.Linear(HIDDEN_DIM // 2, 1),
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


# ============================================================
# 标准化逻辑（与 Normalize.py 一致）
# ============================================================
def _load_norm_params():
    with open(NORM_PARAMS_PATH, 'r', encoding='utf-8') as f:
        return json.load(f)


_normalization_params = None


def _get_norm_params():
    global _normalization_params
    if _normalization_params is None:
        _normalization_params = _load_norm_params()
    return _normalization_params


def normalize_branch_feature(branch_feature, mean, std):
    """标准化边特征第4列（分支长度）。"""
    result = branch_feature.copy()
    length_col = result[:, 3]
    if std > 0:
        result[:, 3] = (length_col - mean) / std
    else:
        result[:, 3] = 0.0
    return result


def normalize_price_matrix(circuit_cost, mean, std, num_nodes):
    """标准化节点特征前 num_nodes 列（回路单价），只对非零值操作。"""
    result = circuit_cost.copy()
    price_matrix = result[:, :num_nodes]
    nonzero_mask = price_matrix != 0
    if nonzero_mask.sum() > 0 and std > 0:
        price_matrix[nonzero_mask] = (price_matrix[nonzero_mask] - mean) / std
    result[:, :num_nodes] = price_matrix
    return result


def normalize_wet_cost(circuit_cost, mean, std, num_nodes):
    """标准化节点特征第 num_nodes 列（湿区成本），只对非零值操作。"""
    result = circuit_cost.copy()
    wet_col = result[:, num_nodes]
    nonzero_mask = wet_col != 0
    if nonzero_mask.sum() > 0 and std > 0:
        wet_col[nonzero_mask] = (wet_col[nonzero_mask] - mean) / std
    result[:, num_nodes] = wet_col
    return result


def normalize_input(branch_feature, circuit_cost, num_nodes):
    """使用预计算统计量对输入特征做标准化。"""
    p = _get_norm_params()
    branch_feature = normalize_branch_feature(branch_feature, p['branch_length_mean'], p['branch_length_std'])
    circuit_cost = normalize_price_matrix(circuit_cost, p['price_mean'], p['price_std'], num_nodes)
    circuit_cost = normalize_wet_cost(circuit_cost, p['wet_cost_mean'], p['wet_cost_std'], num_nodes)
    return branch_feature, circuit_cost


def denormalize_output(total_cost_norm):
    """将标准化输出还原到原始尺度。"""
    p = _get_norm_params()
    return total_cost_norm * p['total_cost_std'] + p['total_cost_mean']


# ============================================================
# 二进制反序列化（与 Java ByteBuffer 格式对应）
# ============================================================
def deserialize_binary(data: bytes):
    """
    从 Java ByteBuffer 序列化的二进制数据中解析字段。
    返回: (x, edge_index, edge_attr) 三个 numpy 数组
    """
    offset = 0
    num_nodes = int(np.frombuffer(data[offset:offset + 4], dtype='>i4')[0])
    offset += 4
    num_edges = int(np.frombuffer(data[offset:offset + 4], dtype='>i4')[0])
    offset += 4

    edge_index_bytes = 2 * num_edges * 4
    edge_attr_bytes = num_edges * EDGE_FEAT_DIM * 4
    x_bytes = num_nodes * NODE_FEAT_DIM * 4

    edge_index = (
        np.frombuffer(data[offset:offset + edge_index_bytes], dtype='>i4')
        .reshape(2, num_edges)
        .byteswap().newbyteorder('=')
        .copy()
    )
    offset += edge_index_bytes

    edge_attr = (
        np.frombuffer(data[offset:offset + edge_attr_bytes], dtype='>f4')
        .reshape(num_edges, EDGE_FEAT_DIM)
        .byteswap().newbyteorder('=')
        .copy()
    )
    offset += edge_attr_bytes

    x = (
        np.frombuffer(data[offset:offset + x_bytes], dtype='>f4')
        .reshape(num_nodes, NODE_FEAT_DIM)
        .byteswap().newbyteorder('=')
        .copy()
    )

    return x, edge_index, edge_attr


# ============================================================
# 模型加载（模块级，只加载一次）
# ============================================================
_device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
_model: Optional[CostModelV2] = None


def get_model():
    global _model
    if _model is None:
        torch.set_num_threads(1)
        torch.set_num_interop_threads(1)
        _model = CostModelV2()
        _model.load_state_dict(torch.load(MODEL_PATH, map_location=_device))
        _model.to(_device)
        _model.eval()
    return _model


# ============================================================
# 推理
# ============================================================
def predict(x: np.ndarray, edge_index: np.ndarray, edge_attr: np.ndarray) -> float:
    """执行一次推理，返回归一化后的预测值。"""
    model = get_model()
    x_t = torch.tensor(x, dtype=torch.float).to(_device)
    edge_index_t = torch.tensor(edge_index, dtype=torch.long).to(_device)
    edge_attr_t = torch.tensor(edge_attr, dtype=torch.float).to(_device)
    with torch.no_grad():
        pred = model(x_t, edge_index_t, edge_attr_t)
    return pred.item()


# ============================================================
# 单次预测入口（接收 stdin 二进制，输出 stdout JSON）
# ============================================================
def main():
    try:
        start = time.time()

        # 1. 读取 stdin 全部二进制数据
        raw = sys.stdin.buffer.read()

        # 2. 解析二进制
        x, edge_index, edge_attr = deserialize_binary(raw)

        # 3. 标准化
        num_nodes = x.shape[0]
        edge_attr, x = normalize_input(edge_attr, x, num_nodes=num_nodes)

        # 4. 推理
        pred_norm = predict(x, edge_index, edge_attr)

        # 5. 反标准化
        predicted_cost = denormalize_output(pred_norm)

        elapsed = (time.time() - start) * 1000

        # 6. 输出 JSON 到 stdout
        result = {
            "predicted_cost": round(float(predicted_cost), 4),
            "elapsed_ms": round(elapsed, 1),
        }
        sys.stdout.write(json.dumps(result))
        sys.stdout.flush()

    except Exception as e:
        # 错误信息输出到 stderr，JSON 错误码输出到 stdout（Java 端可以 try-catch）
        sys.stderr.write(f"PredictError: {e}\n")
        sys.stderr.flush()
        error_json = json.dumps({"error": str(e)})
        sys.stdout.write(error_json)
        sys.stdout.flush()
        sys.exit(1)


if __name__ == '__main__':
    main()
