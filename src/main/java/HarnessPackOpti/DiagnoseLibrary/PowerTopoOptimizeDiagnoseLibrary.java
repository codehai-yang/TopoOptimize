package HarnessPackOpti.DiagnoseLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PowerTopoOptimizeDiagnoseLibrary {
    /** 变种组合数超过此阈值则提示 */
    private static final long VARIANT_COUNT_THRESHOLD = 100000000000L;
    /**
     * 用电器选择的位置变种点不在分支上
     * 仅检查 changeType="1"（指定点可变）的用电器：
     * 其 specifyPoints 中的每个分支点 ID 必须在拓扑图的分支（edges）中存在。
     *
     * @param appPositions 用电器信息列表（含位置变种类型、指定变种点id列表、端点id编号）
     * @param edges        所有分支信息（含分支起点名称、分支终点名称）
     * @param points       所有端点信息（含端点id编号、端点名称）
     * @return 变种点不在分支上的用电器 id 列表
     */
    public List<String> appVariantPointNotOnBranch(
            List<Map<String, Object>> appPositions,
            List<Map<String, Object>> edges,
            List<Map<String, Object>> points) {

        List<String> errorAppIds = new ArrayList<>();

        // 1) 收集所有分支上出现过的端点名称
        java.util.Set<String> branchPointNames = new java.util.HashSet<>();
        for (Map<String, Object> edge : edges) {
            Object start = edge.get("分支起点名称");
            Object end = edge.get("分支终点名称");
            if (start != null) branchPointNames.add(start.toString());
            if (end != null) branchPointNames.add(end.toString());
        }

        // 2) 构建 端点id → 端点名称 映射
        java.util.Map<String, String> pointIdToName = new java.util.HashMap<>();
        for (Map<String, Object> pt : points) {
            Object pid = pt.get("端点id编号");
            Object pname = pt.get("端点名称");
            if (pid != null && pname != null) {
                pointIdToName.put(pid.toString(), pname.toString());
            }
        }

        // 3) 检查 changeType="1" 的用电器
        for (Map<String, Object> app : appPositions) {
            Object changeType = app.get("位置变种类型");
            if (changeType == null || !"1".equals(changeType.toString())) {
                continue;
            }

            Object specifyPoints = app.get("指定变种点id列表");
            if (specifyPoints == null || specifyPoints.toString().trim().isEmpty()) {
                continue; // 未选择变种点，交给 appVariantPointNotSelected 单独报
            }

            String[] ids = specifyPoints.toString().split(",");
            for (String pointId : ids) {
                pointId = pointId.trim();
                if (pointId.isEmpty()) continue;

                String pointName = pointIdToName.get(pointId);
                // 点 ID 未找到 或 点名称不在任何分支上
                if (pointName == null || !branchPointNames.contains(pointName)) {
                    errorAppIds.add(app.get("用电器id").toString());
                    break; // 该用电器已判定有问题，检查下一个
                }
            }
        }

        return errorAppIds;
    }


    /**
     * 用电器未选择指定变种点
     * changeType="1" 表示指定点可变，但 specifyPoints 为空则未选择任何变种点。
     *
     * @param appPositions 用电器信息列表
     * @return 未选择变种点的用电器 id 列表
     */
    public List<String> appVariantPointNotSelected(List<Map<String, Object>> appPositions) {
        List<String> errorAppIds = new ArrayList<>();
        for (Map<String, Object> app : appPositions) {
            Object changeType = app.get("位置变种类型");
            if (changeType == null || !"1".equals(changeType.toString())) {
                continue;
            }
            Object specifyPoints = app.get("指定变种点id列表");
            if (specifyPoints == null || specifyPoints.toString().trim().isEmpty()) {
                errorAppIds.add(app.get("用电器id").toString());
            }
        }
        return errorAppIds;
    }

    /**
     * 用电器可以生成的变种组合数过多
     * 收集所有设置了变种位置（changeType=1或2）的用电器 id，
     * 如果它们的变种点数量乘积超过阈值，则在列表末尾追加一条提示。
     * changeType=0 → 1（默认位置），1 → specifyPoints 个数，2 → 所有分支点总数
     *
     * @param appPositions 用电器信息列表
     * @param edges        所有分支信息（用于算 changeType=2 的总分支点数）
     * @return 超标时返回变种用电器 id 列表，未超标返回空列表
     */
    public List<String> appVariantCountExceeded(List<Map<String, Object>> appPositions,
                                                List<Map<String, Object>> edges) {
        List<String> variantAppIds = new ArrayList<>();
        java.util.Set<String> allBranchPointNames = new java.util.HashSet<>();
        for (Map<String, Object> edge : edges) {
            Object s = edge.get("分支起点名称");
            Object e = edge.get("分支终点名称");
            if (s != null) allBranchPointNames.add(s.toString());
            if (e != null) allBranchPointNames.add(e.toString());
        }
        int totalBranchPoints = allBranchPointNames.size();

        long product = 1L;
        boolean exceeded = false;
        for (Map<String, Object> app : appPositions) {
            Object changeType = app.get("位置变种类型");
            if (changeType == null) continue;
            String ct = changeType.toString();
            if ("0".equals(ct)) continue;  // 默认位置不算变种
            variantAppIds.add(app.get("用电器id").toString());

            int count = 1;
            if ("1".equals(ct)) {
                Object sp = app.get("指定变种点id列表");
                if (sp != null && !sp.toString().trim().isEmpty()) {
                    count = sp.toString().split(",").length;
                }
            } else if ("2".equals(ct)) {
                count = totalBranchPoints;
            }
            product *= count;
            if (product > VARIANT_COUNT_THRESHOLD) {
                exceeded = true;
            }
        }

        if (!exceeded) {
            return new ArrayList<>();
        }
        return variantAppIds;
    }
}
