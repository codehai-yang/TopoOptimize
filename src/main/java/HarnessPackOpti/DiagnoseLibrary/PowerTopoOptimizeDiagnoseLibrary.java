package HarnessPackOpti.DiagnoseLibrary;

import HarnessPackOpti.Algorithm.FindTopoBreak;
import HarnessPackOpti.Algorithm.GenerateTopoMatrix;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return appVariantPointNotOnBranch(appPositions, edges, points, null);
    }

    /**
     * 用电器选择的位置变种点不在分支上
     * 检查每个用电器“最终选择的位置”（优先使用 eleclectionMap 中由 FindElecLocation 计算出的位置名称，
     * 即：用户更改位置 > 固化位置）是否落在拓扑连通图（分支端点名称集合）上；
     * 同时对 changeType="1" 的指定变种点按 id 做同样判断。
     *
     * @param appPositions    用电器信息列表（含位置变种类型、指定变种点id列表、端点id编号）
     * @param edges           所有分支信息（含分支起点名称、分支终点名称）
     * @param points          所有端点信息（含端点id编号、端点名称）
     * @param eleclectionMap  用电器名称 → 最终选择的位置名称（来自 FindElecLocation.getEleclection）；
     *                        若某用电器该值为空且 changeType=1 设有指定变种点，则以指定点的连通性判定
     * @return 变种点不在分支上的用电器 id 列表
     */
    public List<String> appVariantPointNotOnBranch(
            List<Map<String, Object>> appPositions,
            List<Map<String, Object>> edges,
            List<Map<String, Object>> points,
            Map<String, String> eleclectionMap) {

        List<String> errorAppIds = new ArrayList<>();

        // 1) 基于邻接矩阵 + BFS 计算“真正连通”的端点集合（考虑分支打断B、双向导通、排除孤立点）
        //    构建带打断信息的拓扑图：被 分支打断="B" 的分支不参与连通
        List<String> strPointName = new ArrayList<>();
        List<String> endPointName = new ArrayList<>();
        List<List<String>> branchBreakList = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            Object s = edge.get("分支起点名称");
            Object e = edge.get("分支终点名称");
            if (s == null || e == null) continue;
            strPointName.add(s.toString());
            endPointName.add(e.toString());
            Object broken = edge.get("分支打断");
            if (broken != null && "B".equals(broken.toString())) {
                List<String> interrupted = new ArrayList<>();
                interrupted.add(s.toString());
                interrupted.add(e.toString());
                branchBreakList.add(interrupted);
            }
        }
        GenerateTopoMatrix topo = new GenerateTopoMatrix(strPointName, endPointName, branchBreakList);
        topo.adjacencyMatrix();
        topo.addEdge();
        List<List<String>> families = new FindTopoBreak().recognizeBreak(topo.getAdj(), topo.getAllPoint());
        // 连通点集合：只保留“分量规模>1”的点（即至少与另一个点相连，非孤立点）
        Set<String> connectedPointNames = new HashSet<>();
        for (List<String> family : families) {
            if (family != null && family.size() > 1) {
                connectedPointNames.addAll(family);
            }
        }

        // 2) 构建 端点id → 端点名称 映射（仅当未提供 eleclectionMap 时用于回退）
        java.util.Map<String, String> pointIdToName = new java.util.HashMap<>();
        for (Map<String, Object> pt : points) {
            Object pid = pt.get("端点id编号");
            Object pname = pt.get("端点名称");
            if (pid != null && pname != null) {
                pointIdToName.put(pid.toString(), pname.toString());
            }
        }

        // 3) 检查所有用电器（包括 changeType=0 的默认位置）的位置点是否落在连通图上
        for (Map<String, Object> app : appPositions) {
            Object appId = app.get("用电器id");
            if (appId == null) continue;
            String appIdStr = appId.toString();
            String appName = app.get("用电器名称") == null ? null : app.get("用电器名称").toString();

            // 当前位置直接使用 FindElecLocation 计算出的最终位置名称（用户更改 > 固化）
            String curPointName = null;
            if (eleclectionMap != null && appName != null) {
                String sel = eleclectionMap.get(appName);
                if (sel != null && !sel.trim().isEmpty()) {
                    curPointName = sel.trim();
                }
            }

            // 判定是否为 changeType="1" 且设置了指定变种点（用于“当前位置为空”的双重检测）
            Object changeType = app.get("位置变种类型");
            Object specifyPoints = app.get("指定变种点id列表");
            boolean isCt1 = changeType != null && "1".equals(changeType.toString());
            boolean isCt2 = changeType != null && "2".equals(changeType.toString());
            boolean isCt1WithSpecify = isCt1
                    && specifyPoints != null && !specifyPoints.toString().trim().isEmpty();
            // 默认位置为空时仍视为“可通过”的情形：changeType=1 有指定点（交由指定点判定），或 changeType=2（全图可变）
            boolean passWhenEmpty = isCt1WithSpecify || isCt2;

            // 当前位置的连通性判断（双重检测规则）：
            //  - 当前位置非空但不在连通图上 -> 报错
            //  - 当前位置为空：
            //        * 若 changeType=1 有指定点 -> 不直接报错，交由下方“指定点连通性”决定
            //        * 若 changeType=2（全图可变）-> 通过，不报错
            //        * 否则（changeType 为空/0 等，无默认位置又无其它可依赖位置）-> 报错
            if (curPointName != null) {
                if (!connectedPointNames.contains(curPointName)) {
                    if (!errorAppIds.contains(appIdStr)) errorAppIds.add(appIdStr);
                }
            } else {
                if (!passWhenEmpty) {
                    if (!errorAppIds.contains(appIdStr)) errorAppIds.add(appIdStr);
                }
            }

            // 检查 changeType="1" 的指定变种点（仅当设置了指定点）
            if (!isCt1WithSpecify) {
                continue;
            }
            String[] ids = specifyPoints.toString().split(",");
            for (String pointId : ids) {
                pointId = pointId.trim();
                if (pointId.isEmpty()) continue;

                String pointName = pointIdToName.get(pointId);
                if (pointName == null || !connectedPointNames.contains(pointName)) {
                    if (!errorAppIds.contains(appIdStr)) {
                        errorAppIds.add(appIdStr);
                    }
                    break;
                }
            }
        }

        return errorAppIds;
    }


    /**
     * 用电器未选择可变位置 / 默认位置缺失
     * 判定规则（依赖 FindElecLocation 算出的默认位置 eleclectionMap）：
     *  - changeType 为空 或 "0"：必须有默认位置，默认位置为空则报错（无位置可依赖）
     *  - changeType="1"：必须设置指定变种点（specifyPoints），未设置则报错
     *  - changeType="2"：全图可变，无需设置指定点，不报错
     *
     * @param appPositions   用电器信息列表
     * @param eleclectionMap 用电器名称 → 最终选择的默认位置名称（来自 FindElecLocation.getEleclection）
     * @return 缺失可变位置/默认位置的用电器 id 列表
     */
    public List<String> appVariantPointNotSelected(List<Map<String, Object>> appPositions,
                                                   Map<String, String> eleclectionMap) {
        List<String> errorAppIds = new ArrayList<>();
        for (Map<String, Object> app : appPositions) {
            Object appId = app.get("用电器id");
            if (appId == null) continue;
            String appIdStr = appId.toString();
            String appName = app.get("用电器名称") == null ? null : app.get("用电器名称").toString();

            Object changeType = app.get("位置变种类型");
            String ct = changeType == null ? "" : changeType.toString().trim();

            if ("2".equals(ct)) {
                // 全图可变，无需设置指定点，不报错
                continue;
            }

            if ("1".equals(ct)) {
                // 指定点可变：必须设置指定变种点
                Object specifyPoints = app.get("指定变种点id列表");
                if (specifyPoints == null || specifyPoints.toString().trim().isEmpty()) {
                    if (!errorAppIds.contains(appIdStr)) errorAppIds.add(appIdStr);
                }
                continue;
            }

            // changeType 为空 或 "0"：必须有默认位置
            String defaultPos = (eleclectionMap != null && appName != null) ? eleclectionMap.get(appName) : null;
            if (defaultPos == null || defaultPos.trim().isEmpty()) {
                if (!errorAppIds.contains(appIdStr)) errorAppIds.add(appIdStr);
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
