package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FindTopoBreak {
    private List<Integer> allPointNumbers;//所有端点的编号[0,1,2,3,4,5,......]
    private List<List<Integer>> familys;//多个彼此相连的编号组成一个族群,所有族群都存入familys
    private List<Integer> eachFamily;//某一个族群
    private List<Integer> parentCollect;//存放历史出现过的父元素编号
    private List<Integer> parentElement;//父元素parentElement列表中，
    private List<List<Integer>> adj;//邻接表

    /*
    输入参数1：adj邻接列表：以序号形式体现，序号对应的端点名称来自allpoint
             adj举例：[[130, 37], [47, 66, 147, 56], [133, 153], [97, 13, 113, 9],......]
            以adj中第0个子list[130, 37]为例，
             0代表分支起点是allPoint列表中的第0个元素，视为父元素parentElement
             37代表终点是allPoint列表中的第130个元素，37代表终点是allPoint列表中的第130个元素，视为子元素childElement

    输入参数2：allpoint：图中所有端点的清单
             allpoint举例：[尾门中上点, 前舱右纵梁中点, 中控线中点, 后围板内左点, 仪表中通道后点, 后保线右中点, 前保线中点, 右前门湿区中点]
     */

    //检查分支之间是否有断点
    public List<List<String>> recognizeBreak(List<List<Integer>> adj, List<String> allPoint) {

        this.adj = adj;

        List<Integer> parentCollect = new ArrayList<>();
        this.parentCollect = parentCollect;

        List<Integer> eachFamily = new ArrayList<>();
        this.eachFamily = eachFamily;

        List<List<Integer>> familys = new ArrayList<>();
        this.familys = familys;

        List<Integer> allPointNumbers = new ArrayList<>();
        this.allPointNumbers = allPointNumbers;

        List<Integer> parentElement = new ArrayList<>();
        this.parentElement = parentElement;

//        System.out.println("adj:" + adj);

        //获取allPointNumbers的值

        for (String number : allPoint) {
            this.allPointNumbers.add(allPoint.indexOf(number));
        }

        Integer init = this.allPointNumbers.get(0);
        //存放历史出现过的父元素编号,存入初值化值
        this.parentCollect.add(init);

        //父元素parentElement列表中，新增初始化值
        this.parentElement.add(init);

        //所有端点编号中删除首个初始化父元素
        this.allPointNumbers.remove(0);

        //首个初始化父元素加入族群清单
        this.eachFamily.add(init);

        //执行searchElement
        // 获得第一个eachFamily族群中已有哪些编号，说明这些编号彼此相连
        // 获得allPointNumbers中还剩哪些编号，说明这些编号与被删除的编号之间没有连接关系

        searchElement();
//        System.out.println("eachFamily:" + eachFamily);
//        System.out.println("allPointNumbers:" + allPointNumbers);
        List<Integer> temp = new ArrayList<>();
        temp.addAll(this.eachFamily);
        this.familys.add(temp);//第一个eachFamily族群加入familys

//        System.out.println("===================================================");
//        System.out.println("allPointNumbers:"+this.allPointNumbers);
//        System.out.println("familys:"+this.familys);
//        System.out.println("===================================================");

        //若allPointNumbers中已不剩其他元素编号了，则说明分支之间无断开
        //若allPointNumbers中还剩其他元素编号，则要从中进一步确认哪些分支是一个新族群，使用迭代器遍历
        Iterator<Integer> iterator = allPointNumbers.iterator();
        while (iterator.hasNext()) {
            if (this.allPointNumbers.size() > 0) {
                this.eachFamily.clear();
                this.parentElement.clear();
                Integer init2 = this.allPointNumbers.get(0);
                this.eachFamily.add(init2);
                this.parentElement.add(init2);
                this.allPointNumbers.remove(0);
                searchElement();
                List<Integer> temp1 = new ArrayList<>();
                temp1.addAll(this.eachFamily);
                this.familys.add(temp1);

//                System.out.println("===================================================");
//                System.out.println("allPointNumbers:"+this.allPointNumbers);
//                System.out.println("familys:" + this.familys);
//                System.out.println("===================================================");
            }
        }

        //将familys中的分支端点编号，转换为分支端点的名称后，再对外输出
        List<List<String>> familyPointName = new ArrayList<>();
        for(List<Integer> family:this.familys){
            List<String> PointName = new ArrayList<>();
            for(Integer element: family){
                PointName.add(allPoint.get(element));
            }
            familyPointName.add(PointName);
        }
//        System.out.println("familyPointName:" + familyPointName);

        return familyPointName;//格式[[族群A内端点1名称、族群A内端点2名称、族群A内端点3名称、...][族群B内端点1名称、族群B内端点2名称、族群B内端点3名称、...]]
    }

    private void searchElement() {

//        System.out.println("parentElement:" + parentElement);

        List<Integer> newParentElement = new ArrayList<>();//下一级的新父元素清单

        for (Integer parent : parentElement) {//获得每一个父元素

            for (Integer childElement : adj.get(parent)) {//获得父元素对应的每一个子元素childElement
//                System.out.println("childElement:" + childElement);

                //若该子元素仍包含在所有端点编号中，则删除
                if (allPointNumbers.contains(childElement)) {
                    allPointNumbers.removeIf(s -> s.equals(childElement));//所有端点编号中删除该子元素
//                        System.out.println("allPointNumbers:"+allPointNumbers);
                }

                //若族群中未包含该子元素，则加入
                if (!eachFamily.contains(childElement)) {
                    eachFamily.add(childElement);
                }

                //将每个子元素存入下一级的新父元素清单
                if (!parentCollect.contains(childElement))
                    newParentElement.add(childElement);

                //将每个子元素存入历史父元素清单中
                if (!parentCollect.contains(childElement)) {
                    parentCollect.add(childElement);
                }
            }
        }

        parentElement.clear();

        if (newParentElement.size() > 0) {
            parentElement.addAll(newParentElement);
        }


//        System.out.println("newParentElement:" + newParentElement);
//        System.out.println("eachFamily元素个数"+eachFamily.size()+":"+eachFamily);
//        System.out.println("parentCollect:" + parentCollect);
//        System.out.println("________________________________________________________________________");


        //从下一级的新父元素清单中再找出子元素，递归方式搜索
        if (parentElement.size() > 0) {
            searchElement();
        }
    }
}
