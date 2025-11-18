package HarnessPackOpti.DiagnoseLibrary;


import java.util.*;
import java.util.regex.Pattern;

public class TopoDiagnoseLibrary {

    //检查重复的端点名称
    public List<String> repeatPoint(Map<String, Object> HarnessTopo) {
        //将每个端点名称存入列表pointName
        List<Map<String, Object>> points = (List<Map<String, Object>>) HarnessTopo.get("所有端点信息");
        List<String> pointName = new ArrayList<>();
        for (Map<String, Object> k : points) {
            pointName.add((String) k.get("端点名称"));
        }
//        System.out.println("pointName:"+pointName);

        //列表pointName查重，将重复项存入新列表pointDuplicate
        Set<String> duplicates = new HashSet<>();// 创建一个Set来存储发现的重复元素,Set仅存储唯一的元素，不允许有重复的元素。Set接口通常用于存储一组不需要索引元素的场景。
        Set<String> checkedElements = new HashSet<>();// 创建一个Set来存储已经检查过的元素，用于检测重复
        for (String k : pointName) {
            if (checkedElements.contains(k)) {
                // 如果元素已经检查过，且再次出现，则它是重复的
                duplicates.add(k);
            } else {
                // 否则，将元素添加到已检查集合中
                checkedElements.add(k);
            }
        }
        List<String> pointDuplicate = new ArrayList<>(duplicates); // 将重复元素存入新的List中，每个不同的重复元素只存入一次
//        System.out.println("端点名称重复项:" + pointDuplicate);
        return pointDuplicate;
    }


    //检查重复的分支名称
    public List<String> repeatEdge(Map<String, Object> HarnessTopo) {
        //将每个分支名称存入列表edgeName
        List<Map<String, Object>> edges = (List<Map<String, Object>>) HarnessTopo.get("所有分支信息");
        List<String> edgeName = new ArrayList<>();
        for (Map<String, Object> k : edges) {
            edgeName.add((String) k.get("分支名称"));
        }
//        System.out.println("edgeName:"+edgeName);

        //列表edgeName查重，将重复项存入新列表edgeDuplicate
        Set<String> duplicates = new HashSet<>();
        Set<String> checkedElements = new HashSet<>();
        for (String k : edgeName) {
            if (checkedElements.contains(k)) {
                duplicates.add(k);
            } else {
                checkedElements.add(k);
            }
        }
        List<String> edgeDuplicate = new ArrayList<>(duplicates);
//        System.out.println("分支名称重复项:" + edgeDuplicate);
        return edgeDuplicate;
    }


    //检查分支起点名称与分支终点名称是否有缺失
    //只有当strEndPointLack列表中是空的，即元素个数size()=0时，才进行邻接矩阵导通、闭环的检查
    public List<List<String>> strEndPointLack(Map<String, Object> HarnessTopo) {
        List<Map<String, Object>> edges = (List<Map<String, Object>>) HarnessTopo.get("所有分支信息");
        List<String> startLackEdgeName = new ArrayList<>();
        List<String> endLackEdgeName = new ArrayList<>();
        for (Map<String, Object> k : edges) {
//            strPointName.add((String) k.get("分支起点名称"));
//            endPointName.add((String) k.get("分支终点名称"));
//            edgeName.add((String) k.get("分支名称"));
            if (k.get("分支起点名称") == null || k.get("分支起点名称").toString().length() == 0) {
                startLackEdgeName.add((String) k.get("分支id编号"));
            }
            if (k.get("分支终点名称") == null || k.get("分支终点名称").toString().length() == 0) {
                endLackEdgeName.add((String) k.get("分支id编号"));
            }
        }
        List<List<String>> result = new ArrayList<>();
        result.add(startLackEdgeName);
        result.add(endLackEdgeName);

        return result;
    }


    //检查检查所有接口直连编号，编号不重复。
    //检查所有接口直连编号，要求格式正确，”String前缀-String后缀“的组合，例如：A-1，A-2，A-3
    //检查所有接口直连编号，同一个String前缀的编号数量必须≥2。
    public List<List<String>> interfaceError(Map<String, Object> HarnessTopo) {
        //将每个端点接口直连编号存入列表interfaceName
        List<Map<String, Object>> points = (List<Map<String, Object>>) HarnessTopo.get("所有端点信息");
        List<String> interfaceName = new ArrayList<>();
        for (Map<String, Object> k : points) {
            String interfaceCode = (String) k.get("端点接口直连编号");
            if (interfaceCode != null && interfaceCode.length() > 0) {
                interfaceName.add(interfaceCode);
            }
        }
//        System.out.println("端点接口直连编号:" + interfaceName);

        List<List<String>> interfaceError = new ArrayList<>();

        if (interfaceName.size() != 0) {

            //列表interfaceName查重，将重复项存入新列表interfaceDupicate
            Set<String> duplicates = new HashSet<>();
            Set<String> checkedElements = new HashSet<>();
            for (String k : interfaceName) {
                if (checkedElements.contains(k)) {
                    duplicates.add(k);
                } else {
                    checkedElements.add(k);
                }
            }
            List<String> interfaceDupicate = new ArrayList<>(duplicates);
//            System.out.println("端点接口直连编号重复项:" + interfaceDupicate);


            //检查所有接口直连编号，符合格式”String前缀-String后缀“
            //若符合上述这项，进一步检查同一个String前缀的编号数量必须≥2，即必须有重复项
            String regex = "^.+-.+$"; //正则表达式"String-String"的格式
            Pattern pattern = Pattern.compile(regex);//正则表达式编译成一个 Pattern 对象,再用cmpile方法解析翻译
            List<String> wrongRegex = new ArrayList<>();
            List<String> firstPart = new ArrayList<>();
            for (String k : interfaceName) {
                if (!pattern.matcher(k).matches()) { // 如果当前字符串不符合正则表达式定义的格式，则....
                    wrongRegex.add(k);
                } else {
                    firstPart.add(k.split("-")[0]);//使用split方法按'-'分割字符串,取前半部分,存入列表
                }
            }
//            System.out.println("端点接口直连编号不符合String-String格式的项:" + wrongRegex);
//            System.out.println("端点接口直连编号的前半部分:" + firstPart);
            Set<String> seen = new HashSet<>(); // 用于存储已经看到的 接口编号前缀
            Set<String> repeat = new HashSet<>(); // 用于存储重复的 接口编号前缀
            List<String> uniqueFirstPart = new ArrayList<>(); // 存储没有重复的 接口编号前缀
            for (String k : firstPart) {
                if (seen.contains(k)) {
                    // 如果元素已经出现过，认为是重复的
                    repeat.add(k);
                } else {
                    // 否则，将元素添加到seen集合
                    seen.add(k);
                }
            }
            // 从seen集合中移除所有重复元素，剩下的就是没有重复的元素
            seen.removeAll(repeat);
            uniqueFirstPart.addAll(seen);
            //根据接口编号的前缀，找出对应的接口编号
            List<String> uniqueInterfaceName = new ArrayList<>(); // 存储没有重复的接口编号
            for (String k : interfaceName) {
                for (String m : uniqueFirstPart) {
                    if (k.split("-")[0].equals(m)) {
                        uniqueInterfaceName.add(k);
                    }
                }
            }
//            System.out.println("端点接口直连编号的String前缀出现次数必须＞1，不符合该要求的是:" + uniqueInterfaceName);
            interfaceError.add(interfaceDupicate);
            interfaceError.add(wrongRegex);
            interfaceError.add(uniqueInterfaceName);
        }
        return interfaceError;
    }


    //检查分支长度是否缺
    // 参考长度 & 用户确认的分支长度，两者至少有一个的数值＞0，否则判定为分支长度缺失
    public List<String> branchLengthLack(Map<String, Object> bunLenInfo) {
        List<Map<String, Object>> edges = (List<Map<String, Object>>) bunLenInfo.get("所有分支信息");
        List<String> lengthLack = new ArrayList<>();//存放缺失长度的分支名称
        for (Map<String, Object> edge : edges) {
//           判断这个分支长度是否缺失 fasle 缺失 true：不缺失
            Boolean flag = false;

//            判断用户确认长度是 是否存在 null  空 非数等情况    不存在  将flag改为true
            if (edge.get("用户确认的分支长度") !=null && !edge.get("用户确认的分支长度").toString().isEmpty() &&
                    !edge.get("用户确认的分支长度").toString().equals("0")
                    && (edge.get("用户确认的分支长度").toString().matches("^[0-9]+$")
                    || edge.get("用户确认的分支长度").toString().matches("^[0-9]*\\.[0-9]+$"))) {
                flag = true;
            }
//            用户确认长度已经存在  不需要查看 参考长度
            if (!flag){
//           参考长度是 是否存在 null  空 非数等情况    不存在  将flag改为true
                if (edge.get("参考长度") !=null && !edge.get("参考长度").toString().isEmpty() &&
                        !edge.get("参考长度").toString().equals("0")
                        && (edge.get("参考长度").toString().matches("^[0-9]+$")
                        || edge.get("参考长度").toString().matches("^[0-9]*\\.[0-9]+$"))) {
                    flag = true;
                }
            }
//            将用户确认长度和参考长度都不存在的情况的分支名称找出来
            if (!flag){
                lengthLack.add(edge.get("分支名称").toString());
            }
        }

        return lengthLack;
    }


    //检查关键尺寸长度是否缺失
    public List<String> criticalDimensionLack(Map<String, Object> bunLenInfo) {
        List<Map<String, Object>> criticalDimensions = (List<Map<String, Object>>) bunLenInfo.get("所有关键尺寸信息");
        List<String> dimensionLackID = new ArrayList<>();//存放缺失长度值的关键尺寸ID
        for (Map<String, Object> k : criticalDimensions) {
            String sizeValue = String.valueOf(k.get("关键尺寸长度"));
            String dimensionID = (String) k.get("关键尺寸id编号");
            if (sizeValue == null || sizeValue.isEmpty() || sizeValue == "null") {
                dimensionLackID.add(dimensionID);
                //.matches("^[0-9]+$")正则表达式，表示是整数
                //.matches("^[0-9]*\\.[0-9]+$")正则表达式，表示是小数
            } else if (sizeValue.matches("^[0-9]+$") || sizeValue.matches("^[0-9]*\\.[0-9]+$")) {
                if (Double.valueOf(sizeValue) <= 0.0) {
                    dimensionLackID.add(dimensionID);
                }
            }

        }
//        System.out.println("缺失长度值的关键尺寸ID:" + dimensionLackID);
        return dimensionLackID;
    }


    //检查项目基本信息是否缺失：项目名称缺失、左右驾信息缺失、车辆类型缺失、动力类型缺失
    public List<String> projectInfoLack(Map<String, Object> bunLenInfo) {
        HashMap<String, Object> projectInfo = (HashMap<String, Object>) bunLenInfo.get("项目基本信息");
        String drivingType = (String) projectInfo.get("左右驾信息");
        String bodyStyle = (String) projectInfo.get("车辆类型");
        String powerType = (String) projectInfo.get("动力类型");

        List<String> projectInfoLack = new ArrayList<>();//存放缺失信息的项目基本信息
        if (drivingType == null || drivingType.length() <= 0) {
            projectInfoLack.add("左右驾信息缺失");
        }
        if (bodyStyle == null || bodyStyle.length() <= 0) {
            projectInfoLack.add("车辆类型缺失");
        }
        if (powerType == null || powerType.length() <= 0) {
            projectInfoLack.add("动力类型缺失");
        }

        return projectInfoLack;
    }

    //    分支通断是否缺失
    public List<String> branchLinkInfoLack(List<Map<String, Object>> edges) {
        List<String> edgeId = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            if (edge.get("分支打断") == null || edge.get("分支打断").toString().length()==0) {
                edgeId.add(edge.get("分支id编号").toString());
            }
        }
        return edgeId;
    }
//   端点干湿信息缺失
public List<String> pointWaterParamInfoLack(List<Map<String, Object>> points) {
    List<String> edgeId = new ArrayList<>();
    for (Map<String, Object> edge : points) {
        if (edge.get("端点干湿") == null || edge.get("端点干湿").toString().length()==0) {
            edgeId.add(edge.get("端点id编号").toString());
        }
    }
    return edgeId;
}

}