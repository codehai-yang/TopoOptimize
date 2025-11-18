package HarnessPackOpti.GraphGenerate;

import HarnessPackOpti.InfoRead.ReadBunLenInfo;
import HarnessPackOpti.JsonToMap;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class GenerateTopoGraph extends JFrame {

    private List<String> strNode;
    private List<String> endNode;
    private List<Double> strNodeCoordinateX;
    private List<Double> strNodeCoordinateY;
    private List<Double> endNodeCoordinateX;
    private List<Double> endNodeCoordinateY;
    private List<String> inlineInfo;//存放分支打断[C,S,C,C,S....]
    private Integer topo_X_offset;//分支X坐标点的偏移量
    private Integer topo_Y_offset;//分支Y坐标点的偏移量
    private Integer topo_X_scale;//分支X坐标点的缩放倍率
    private Integer topo_Y_scale;//分支Y坐标点的缩放倍率

    public void setStrNode(List<String> strNode) {
        this.strNode = strNode;
    }

    public void setEndNode(List<String> endNode) {
        this.endNode = endNode;
    }

    public void setStrNodeCoordinateX(List<Double> strNodeCoordinateX) {
        this.strNodeCoordinateX = strNodeCoordinateX;
    }

    public void setStrNodeCoordinateY(List<Double> strNodeCoordinateY) {
        this.strNodeCoordinateY = strNodeCoordinateY;
    }

    public void setEndNodeCoordinateX(List<Double> endNodeCoordinateX) {
        this.endNodeCoordinateX = endNodeCoordinateX;
    }

    public void setEndNodeCoordinateY(List<Double> endNodeCoordinateY) {
        this.endNodeCoordinateY = endNodeCoordinateY;
    }

    public void setInlineInfo(List<String> inlineInfo) {
        this.inlineInfo = inlineInfo;
    }

    public void setTopo_X_offset(Integer topo_X_offset) {
        this.topo_X_offset = topo_X_offset;
    }

    public void setTopo_Y_offset(Integer topo_Y_offset) {
        this.topo_Y_offset = topo_Y_offset;
    }

    public void setTopo_X_scale(Integer topo_X_scale) {
        this.topo_X_scale = topo_X_scale;
    }

    public void setTopo_Y_scale(Integer topo_Y_scale) {
        this.topo_Y_scale = topo_Y_scale;
    }


    public GenerateTopoGraph() {
        super();//调用父类
        initialize();
    }

    public void initialize() {//初始化方法
        this.setSize(1700, 1000);//设置窗体大小
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);//设置窗体关闭模式
        MyCanvas c = new MyCanvas();
        add(c);//把画布MyCanvas添加到窗体中。
        this.setTitle("线束拓扑图");
    }


    public class MyCanvas extends Canvas {

        private BufferedImage backgroundImage;

        public void paint(Graphics g) {
            Graphics2D g4 = (Graphics2D) g;//获取Graphics2D对象
            //设置抗锯齿
            g4.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            //设置背景颜色
            g4.setBackground(Color.WHITE);
            //画一个背景同尺寸的矩形
            g4.setColor(Color.WHITE);
            g4.fillRect(0, 0, 1700, 1000);


//            //设置小车背景
//            try {
//                backgroundImage = ImageIO.read(new File("data/小车图.png"));
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//            g4.drawImage(backgroundImage, 50, 0, this);


            //为小车设置前围板分界线
            g4.setColor(Color.LIGHT_GRAY);
            float[] dashPattern = {10, 5}; // 虚线
            g4.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dashPattern, 0));//设置线宽
            g4.drawLine(450, 160, 450, 750);


            //—————————————————————————————————————画拓扑图—————————————————————————————————————
            //将起点与终点名称融合,起点与终点X坐标融合,起点与终点Y坐标融合。
            List<String> strEndNode = new ArrayList<>();
            strEndNode.addAll(strNode);
            strEndNode.addAll(endNode);
            List<Double> strEndNodeCoordinateX = new ArrayList<>();
            strEndNodeCoordinateX.addAll(strNodeCoordinateX);
            strEndNodeCoordinateX.addAll(endNodeCoordinateX);
            List<Double> strEndNodeCoordinateY = new ArrayList<>();
            strEndNodeCoordinateY.addAll(strNodeCoordinateY);
            strEndNodeCoordinateY.addAll(endNodeCoordinateY);

            //获取inline通道的起点坐标与终点坐标
//            List<String> inlineTunnel = readPowDistriHarTunnel.getTunnelInfo().get(3);
            List<Double> unBroTunstrNodeCoordX = new ArrayList<>();
            List<Double> unBroTunstrNodeCoordY = new ArrayList<>();
            List<Double> unBroTunendNodeCoordX = new ArrayList<>();
            List<Double> unBroTunendNodeCoordY = new ArrayList<>();
            for (int i = 0; i < inlineInfo.size(); i++) {
                if (inlineInfo.get(i).equals("S")) {
                    unBroTunstrNodeCoordX.add(strNodeCoordinateX.get(i));
                    unBroTunstrNodeCoordY.add(-strNodeCoordinateY.get(i));
                    unBroTunendNodeCoordX.add(endNodeCoordinateX.get(i));
                    unBroTunendNodeCoordY.add(-endNodeCoordinateY.get(i));
                }
            }

            //每一个端点都用灰色显示作为背景
            for (int i = 0; i < strNodeCoordinateX.size(); i++) {
                g4.setColor(Color.LIGHT_GRAY);
                //坐标值String转int,坐标转换
                int X = strEndNodeCoordinateX.get(i).intValue() * topo_X_scale + topo_X_offset;
                int Y = -strEndNodeCoordinateY.get(i).intValue() * topo_Y_scale + topo_Y_offset;

                //画端点
                int diameter = 3;//设置端点圆的直径
                g4.fillArc(X - diameter / 2, Y - diameter / 2, diameter, diameter, 0, 360);
            }

            //设置线宽
            g4.setStroke(new BasicStroke(3));

            //画所有分支与端点
            for (int i = 0; i < strNodeCoordinateX.size(); i++) {
                //设置分支颜色
                g4.setColor(Color.LIGHT_GRAY);
                //坐标值String转int,坐标转换
                int strX = strNodeCoordinateX.get(i).intValue() * topo_X_scale + topo_X_offset;
                int strY = -strNodeCoordinateY.get(i).intValue() * topo_Y_scale + topo_Y_offset;
                int endX = endNodeCoordinateX.get(i).intValue() * topo_X_scale + topo_X_offset;
                int endY = -endNodeCoordinateY.get(i).intValue() * topo_Y_scale + topo_Y_offset;
                //画线
                g4.drawLine(strX, strY, endX, endY);
                //画端点
                int diameter = 10;//设置端点圆的直径
                g4.setColor(Color.RED);
                g4.fillArc(strX - diameter / 2, strY - diameter / 2, diameter, diameter, 0, 360);
                g4.fillArc(endX - diameter / 2, endY - diameter / 2, diameter, diameter, 0, 360);
            }

            //画打断的分支与端点，覆盖到所有分支上
            for (int i = 0; i < unBroTunstrNodeCoordX.size(); i++) {
                //设置分支颜色
                g4.setColor(Color.BLACK);

                //坐标值String转int,坐标转换
                int strX = unBroTunstrNodeCoordX.get(i).intValue() * topo_X_scale + topo_X_offset;
                int strY = unBroTunstrNodeCoordY.get(i).intValue() * topo_Y_scale + topo_Y_offset;
                int endX = unBroTunendNodeCoordX.get(i).intValue() * topo_X_scale + topo_X_offset;
                int endY = unBroTunendNodeCoordY.get(i).intValue() * topo_Y_scale + topo_Y_offset;
                //画线
                g4.drawLine(strX, strY, endX, endY);
                //画端点
                int diameter = 10;//设置端点圆的直径
                g4.setColor(Color.RED);
                g4.fillArc(strX - diameter / 2, strY - diameter / 2, diameter, diameter, 0, 360);
                g4.fillArc(endX - diameter / 2, endY - diameter / 2, diameter, diameter, 0, 360);
            }

            //显示分支两侧侧点的名字
            for (int i = 0; i < strNodeCoordinateX.size(); i++) {
                //坐标值String转int,坐标转换
                int X = strEndNodeCoordinateX.get(i).intValue() * topo_X_scale + topo_X_offset;
                int Y = -strEndNodeCoordinateY.get(i).intValue() * topo_Y_scale + topo_Y_offset;
                //创建字体
                Font font = new Font("宋体", Font.BOLD, 12);
                g4.setFont(font);
                //字体颜色
//                Random c= new Random();
//                g4.setColor(new Color(c.nextInt(255), c.nextInt(255), c.nextInt(255)));
                g4.setColor(Color.RED);
                //绘制文本
                Random r = new Random();
                int PosNegOne = r.nextInt(2) == 0 ? -1 : 1;//正负1

                //随机数
                g4.drawString(strEndNode.get(i), X+5*PosNegOne, Y+5*PosNegOne);
//                g4.drawString(strEndNode.get(i), X, Y);
            }



        }
    }


    public void DrawTopo(String fileStringFormat,List<String> edgeName){
        //edgeName是要显示的分支清单
        //fileStringFormat是要导入的json格式txt拓扑文件
    }
    public static void main(String[] args) throws Exception {
        //读取整个文件内容，并转为到字符串
        File file = new File("data/DataCoopWithEB/整车信息-8.2.txt");
        String jsonContent = new String(Files.readAllBytes(file.toPath()));//将文件中内容转为字符串

        //读取到的Json格式字符串转换为Map
        JsonToMap jsonToMap = new JsonToMap();
        Map<String, Object> mapFile = jsonToMap.TransJsonToMap(jsonContent);

        //读取Map中的拓扑信息
        ReadBunLenInfo readBunLenInfo = new ReadBunLenInfo();
        Map<String, Object> bunLenInfo = readBunLenInfo.getBunLenInfo(mapFile);

        List<String> strNode = new ArrayList<>();
        List<String> endNode = new ArrayList<>();
        List<Double> strNodeCoordinateX = new ArrayList<>();
        List<Double> strNodeCoordinateY = new ArrayList<>();
        List<Double> endNodeCoordinateX = new ArrayList<>();
        List<Double> endNodeCoordinateY = new ArrayList<>();
        List<String> inlineInfo = new ArrayList<>();

        for (Map<String, Object> k : (List<Map<String, Object>>) bunLenInfo.get("所有分支信息")) {
            strNode.add((String) k.get("分支起点名称"));
            endNode.add((String) k.get("分支终点名称"));
            strNodeCoordinateX.add((Double) k.get("分支起点x坐标"));
            strNodeCoordinateY.add((Double) k.get("分支起点y坐标"));
            endNodeCoordinateX.add((Double) k.get("分支终点x坐标"));
            endNodeCoordinateY.add((Double) k.get("分支终点y坐标"));
            inlineInfo.add((String) k.get("分支打断"));
        }

        GenerateTopoGraph g = new GenerateTopoGraph();
        g.setStrNode(strNode);
        g.setEndNode(endNode);
        g.setStrNodeCoordinateX(strNodeCoordinateX);
        g.setStrNodeCoordinateY(strNodeCoordinateY);
        g.setEndNodeCoordinateX(endNodeCoordinateX);
        g.setEndNodeCoordinateY(endNodeCoordinateY);
        g.setInlineInfo(inlineInfo);
        g.setTopo_X_scale(13);//分支X坐标点的缩放倍率
        g.setTopo_Y_scale(13);//分支Y坐标点的缩放倍率
        g.setTopo_X_offset(400);//分支X坐标点的偏移量
        g.setTopo_Y_offset(450);//分支Y坐标点的偏移量

        g.setVisible(true);
    }
}



