package HarnessPackOpti.Algorithm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// adj举例：[[130, 37], [47, 66, 147, 56], [133, 153], [97, 13, 113, 9],......]
// 以adj中第0个子list[130, 37]为例，
// 0代表分支起点是allPoint列表中的第0个元素，
// 130代表终点是allPoint列表中的第130个元素，37代表终点是allPoint列表中的第37个元素，


public class GenerateTopoMatrix {
    private int V; // 节点的数量
    private List<List<Integer>> adj; // 邻接列表：[第一行，第二行，第三行....]
    private int[][] matrix; // 邻接矩阵 int[rows][columns]

    public List<List<Integer>> getAdj() {
//        System.out.println("adj:"+adj);
        return adj;
    }

    public List<String> getAllPoint() {
        return allPoint;
    }//所有端点的名称

    private List<String> allPoint;//邻接矩阵的表头：[xx,xx,xx,xx,.....]
    private List<String> startPoint;//所有分支的起点（双向）
    private List<String> endPoint;//所有分支的终点（双向）

    public List<Integer> getStartPointRowNumber() {
        return startPointRowNumber;
    }

    public List<Integer> getEndPointColumnNumber() {
        return endPointColumnNumber;
    }

    private List<Integer> startPointRowNumber;//每个分支起点所在的行数
    private List<Integer> endPointColumnNumber;//每个分支终点的列数

    //获取邻接矩阵基本信息
    public GenerateTopoMatrix(List<String> startPointName, List<String> endPointName, List<List<String>>branchBreakList) {
        List<String> startPoint = new ArrayList<>();
        List<String> endPoint = new ArrayList<>();

        //获得所有分支起点（双向）、分支终点（双向）。确保有向图能双向导通，为形成完整邻接矩阵
        startPoint.addAll(startPointName);
        startPoint.addAll(endPointName); //起点列表加入：分支起点+分支终点
        endPoint.addAll(endPointName);
        endPoint.addAll(startPointName);//终点列表加入：分支终点+分支起点
       Set<Integer> localAddress=new HashSet<>();

       //拿到断点分支的起点和终点在起点列表和终点列表的索引
        for (List<String> list : branchBreakList) {
            List<Integer> startPositions = findPositions(startPoint, list.get(0));
            for (Integer position : startPositions) {
                String s = endPoint.get(position);
                if (s.equals(list.get(1))){
                    localAddress.add(position);
                }
            }
            List<Integer> endPositions = findPositions(startPoint, list.get(1));
            for (Integer position : endPositions) {
                String s = endPoint.get(position);
                if (s.equals(list.get(0))){
                    localAddress.add(position);
                }
            }

        }
        //删除断点分支的起点和终点
        removeElementsAtPositions(startPoint,localAddress.stream().collect(Collectors.toList()));
        removeElementsAtPositions(endPoint,localAddress.stream().collect(Collectors.toList()));
        this.startPoint = startPoint;
        this.endPoint = endPoint;
//        System.out.println("startPoint:"+startPoint);
//        System.out.println("endPoint:"+endPoint);

        //获得邻接矩阵的表头
        Set<String> set = new HashSet<>();// 使用HashSet来去重
        set.addAll(this.startPoint);// 将第一个列表的元素添加到HashSet中
        set.addAll(this.endPoint);// 将第二个列表的元素添加到HashSet中，自动去重
        List<String> allPoint = new ArrayList<>();
        allPoint.addAll(set);// 将去重后的元素添加到新列表中
        this.allPoint = allPoint;
//        System.out.println("allPoint个数"+allPoint.size()+": "+allPoint);

        //获得节点的数量
        Integer V = this.allPoint.size();
        this.V=V;
//        System.out.println("V:"+V);

        //获得每个分支起点所在的行数、每个分支终点的列数，基于邻接矩阵的表头来定
        List<Integer> startPointRowNumber = new ArrayList<>();
        List<Integer> endPointColumnNumber = new ArrayList<>();
        //获取每个分支起点所在的行数
        for(String k:this.startPoint){
            for(int i=0;i<this.V;i++){
                if(k.equals(this.allPoint.get(i))){
                    startPointRowNumber.add(i);
                }
            }
        }
        //获取每个分支终点的列数
        for(String k:this.endPoint){
            for(int i=0;i<this.V;i++){
                if(k.equals(this.allPoint.get(i))){
                    endPointColumnNumber.add(i);
                }
            }
        }
        this.startPointRowNumber=startPointRowNumber;
        this.endPointColumnNumber=endPointColumnNumber;
//        System.out.println("startPointRowNumber个数"+startPointRowNumber.size()+": "+startPointRowNumber);
//        System.out.println("endPointColumnNumber个数"+endPointColumnNumber.size()+": "+endPointColumnNumber);
    }

    //构建邻接矩阵
    public void adjacencyMatrix() {
        this.adj = new ArrayList<>(this.V);//V行V列的列表
        for (int i = 0; i < this.V; i++) {
            this.adj.add(new ArrayList<>());
        }
        this.matrix = new int[this.V][this.V]; // 初始化邻接矩阵，所有值为0
    }

    //添加边，即添加邻接矩阵内的元素
    public void addEdge() {
        for(int i=0;i<this.startPointRowNumber.size();i++){
            // 添加边到邻接表adj
            // adj举例：[[130, 37], [47, 66, 147, 56], [133, 153], [97, 13, 113, 9],......]
            // 以adj中第0个子list[130, 37]为例，
            // 0代表分支起点是allPoint列表中的第0个元素，
            // 130代表终点是allPoint列表中的第130个元素，37代表终点是allPoint列表中的第37个元素，
            this.adj.get(this.startPointRowNumber.get(i)).add(this.endPointColumnNumber.get(i));
            this.matrix[this.startPointRowNumber.get(i)][this.endPointColumnNumber.get(i)] = 1; // 在邻接矩阵中标记存在边
        }
//        System.out.println("adj:"+adj);
    }


    public static List<Integer> findPositions(List<String> list, String target) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equals(target)) {
                positions.add(i);
            }
        }
        return positions;
    }



    public static void removeElementsAtPositions(List<String> list, List<Integer> positionsToRemove) {
        // 将位置列表排序以便从后向前删除元素，避免索引混乱
        //终点与起点倒置
        positionsToRemove.sort((a, b) -> b - a);
        for (Integer position : positionsToRemove) {
            if (position >= 0 && position < list.size()) {
                list.remove(position.intValue());
            }
        }
    }

    // 打印邻接矩阵
    public void printMatrix() {
        for (int i = 0; i < this.V; i++) {
            System.out.print("第"+i+"行: ");
            for (int j = 0; j < this.V; j++) {
                System.out.print(matrix[i][j]);
            }
            System.out.println();
        }
    }
}
