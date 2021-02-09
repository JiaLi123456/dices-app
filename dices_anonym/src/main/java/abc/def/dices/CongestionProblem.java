package abc.def.dices;
import io.jenetics.ext.moea.Vec;
import io.jenetics.prog.ProgramGene;
import org.onosproject.net.*;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CongestionProblem {
   // private static final int LOWER_BOUND = 0;
    private final Logger log = LoggerFactory.getLogger(getClass());

    private TopologyService topologyService;
    private LinkService linkService;
    private HostService hostService;
    private MonitorUtil monitorUtil;
    private SearchRunner runner;

    private Map<SrcDstPair, Long> sdTxBitsMap;
    private Map<Link, Long> simLinkThroughputMap;

    private List<SrcDstPair> curSDList;
    private Map<SrcDstPair, Path> sdAltPathListMap;
    private Map<SrcDstPair, List<Link>> sdCurrentPathMap;

    public CongestionProblem(TopologyService topologyService, LinkService linkService, HostService hostService, MonitorUtil monitorUtil) {
        this.topologyService = topologyService;
        this.linkService = linkService;
        this.hostService = hostService;
        this.monitorUtil = monitorUtil;

        this.sdTxBitsMap = new HashMap<>();
        this.simLinkThroughputMap = new HashMap<>();
        this.sdAltPathListMap = new HashMap<>();
        this.sdCurrentPathMap = new HashMap<>();

        setCurSDPath();

       // setNumberOfVariables();
      //  setNumberOfObjectives(3);
        //setName("CongestionProblem");
       // setLowerUpperLimit();
    }
    //建立目前所有（起点，终点）对及路径的对应关系
    private void setCurSDPath() {
        Set<FlowEntry> flowEntrySet = monitorUtil.getAllCurrentFlowEntries();
        Set<SrcDstPair> sdSet = monitorUtil.getAllSrcDstPairs(flowEntrySet);
        curSDList = new ArrayList<>(sdSet);

        Iterator<SrcDstPair> it = curSDList.iterator();
        while (it.hasNext()) {
            SrcDstPair sd = it.next();
            List<Link> dIdPath = monitorUtil.getCurrentPath(sd);
            if (dIdPath == null) {
                it.remove();
                continue;
            }
            sdCurrentPathMap.put(sd, dIdPath);
        }
        log.info("Set current (src,dst) paths");
    }
//           LOWER_BOUND=0
//            upperLimit=allPathSet.size()-1, allPathSet=getKShortestPaths
/*
    private void setLowerUpperLimit() {
        List<Integer> lowerLimit = new ArrayList<>(getNumberOfVariables());
        List<Integer> upperLimit = new ArrayList<>(getNumberOfVariables());

        for (SrcDstPair sd : curSDList) {
            Host srcHost = hostService.getHost(HostId.hostId(sd.src));
            DeviceId srcDevId = srcHost.location().deviceId();
            Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
            DeviceId dstDevId = dstHost.location().deviceId();

            Set<Path> allPathSet =
                    topologyService.getKShortestPaths(
                            topologyService.currentTopology(),
                            srcDevId, dstDevId,
                            DynamicLinkWeight.DYNAMIC_LINK_WEIGHT,
                            Config.MAX_NUM_PATHS);
            lowerLimit.add(LOWER_BOUND);
            upperLimit.add(allPathSet.size()-1);

            sdAltPathListMap.put(sd, new ArrayList<>(allPathSet));
        }

        setLowerLimit(lowerLimit);
        setUpperLimit(upperLimit);
        log.info("Set upper limits " + upperLimit);
    }

    /*
    private void setNumberOfVariables() {
        setNumberOfVariables(curSDList.size());
        log.info("Set number of variables: " + curSDList.size());
    }
*/

    public CongestionProblem prepareSearch() {
        initSimLinkThroughputMap();
        updateSDTxBitsMap();
        return this;
    }
    //throughput全是0-初始值
    private void initSimLinkThroughputMap() {
        for (Link l : linkService.getLinks()) {
            simLinkThroughputMap.put(l, new Long(0));
        }
    }
    //单流接收的bit数map
    private void updateSDTxBitsMap() {
        for (SrcDstPair sd : curSDList) {
            long deltaTxBits = monitorUtil.getTxBitsPerSec(sd);
            int srcCnt = cntSameSrcInCurFlows(sd);
            deltaTxBits /= srcCnt;
            sdTxBitsMap.put(sd, deltaTxBits);
            /*
            Host srcHost = hostService.getHost(HostId.hostId(sd.src));
            DeviceId srcId = srcHost.location().deviceId();
            Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
            DeviceId dstId = dstHost.location().deviceId();
            log.error("Demand: {}/{} ->  {}/{} {}",
                      srcHost.ipAddresses(), srcId, dstHost.ipAddresses(), dstId, deltaTxBits/1000/1000);
                      */

        }
    }
    //当前流中有相同起点的（起点，终点）对的个数
    private int cntSameSrcInCurFlows(SrcDstPair sd) {
        int cnt = 0;
        for (SrcDstPair cSD : curSDList) {
            if (cSD.src.equals(sd.src)) {
                cnt++;
            }
        }
        return cnt;
    }

    public void setSearchRunner(SearchRunner runnner) {
        this.runner = runnner;
    }

    //private int cnt = 0;


    //设置3个fitness
    public  Vec<double[]> fitness(ProgramGene<Double> tree) {
        /*
        if (++cnt % 1000 == 0) {
            List<IntegerSolution> solutions = runner.getResult();
            IntegerSolution knee = runner.getKneeSolution(solutions);
            if (knee != null) {
                log.error("Cnt {},{},{},{}", cnt, knee.getObjective(0), knee.getObjective(1), knee.getObjective(2));
            }
        }
        */
        Map<SrcDstPair, Path> newSolution=simLink(tree);
        initSimLinkThroughputMap();
        //这里是simulate更新utilization的方法，增加新路径的流量
        updateSimLinkThroughputMap(newSolution);

        double eu = estimateMaxLinkUtilization();
        //solution.setObjective(0, eu);

        long costByDiff = calculateDiffFromOrig(newSolution);
        if (costByDiff == 0) {
            costByDiff = Config.LARGE_NUM;
        }
        //solution.setObjective(1, costByDiff);

        //delay值更新
        long totalDelay = sumDelay(newSolution);
        if (eu > Config.UTILIZATION_THRESHOLD) {
            totalDelay = Config.LARGE_NUM;
        }
       // solution.setObjective(2, totalDelay);
        return Vec.of(eu,costByDiff,totalDelay);

        /*
        if (cnt % 1000 == 0) {
            log.error("Sol {},{},{},{}", cnt, solution.getObjective(0), solution.getObjective(1), solution.getObjective(2));
        }
        */
    }

//根据tree求出新的links
    public Map<SrcDstPair, Path> simLink(ProgramGene<Double> tree) {
        for (SrcDstPair sd : curSDList) {
            Host srcHost = hostService.getHost(HostId.hostId(sd.src));
            DeviceId srcDevId = srcHost.location().deviceId();
            Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
            DeviceId dstDevId = dstHost.location().deviceId();

            DynamicLinkWeight linkWeight = (DynamicLinkWeight)DynamicLinkWeight.DYNAMIC_LINK_WEIGHT;
            linkWeight.lock();
            for (Link l : linkService.getLinks()) {
                long delay = monitorUtil.getDelay(l);
                double newUtilization=estimateUtilization(l);
                long oldLinkWeight=linkWeight.getLinkWeight(l);
                double newLinkWeight=tree.eval((double)oldLinkWeight,newUtilization,(double)delay);
                linkWeight.setLinkWeight(l, (int)Math.round(newLinkWeight));
                //log.error("Link {} -> {}: util {}", l.src().deviceId(), l.dst().deviceId(), runner.estimateUtilization(l));
                //log.error("Link {} -> {}: weight {}", l.src().deviceId(), l.dst().deviceId(), (int)Math.round(nWeight));
            }
            linkWeight.unlock();
            Set<Path> allPathSet =
                    topologyService.getKShortestPaths(
                            topologyService.currentTopology(),
                            srcDevId, dstDevId,
                            DynamicLinkWeight.DYNAMIC_LINK_WEIGHT,
                            Config.MAX_NUM_PATHS);

            sdAltPathListMap.put(sd, (Path)(allPathSet.toArray()[0]));
        }

       //Map<SrcDstPair, Path> result=new HashMap<>();

        return sdAltPathListMap;
    }
//这里不能用List link 要考虑多个起点终点对的情况，搞个hashMap吧，key是srcdstpair 值是links
    private long sumDelay(Map<SrcDstPair, Path> newLinksForPair) {
        long sum = 0;
        for (SrcDstPair key : newLinksForPair.keySet()) {
            Path sPath = newLinksForPair.get(key);
            for (Link l : sPath.links()) {
                sum += monitorUtil.getDelay(l);
            }
        }

        return sum;
    }

    private long calculateDiffFromOrig(Map<SrcDstPair, Path> newLinksForPair) {
        int distSum = 0;
       // int pairNum=newLinksForPair.keySet().size();
        for (SrcDstPair key : newLinksForPair.keySet()) {

            SrcDstPair sd = key;
            Path sPath =  newLinksForPair.get(sd);
            List<Link> sLinkPath = sPath.links();
            List<Link> oLinkPath = sdCurrentPathMap.get(sd);
            int dist = editLCSDistance(oLinkPath, sLinkPath);
            distSum += dist;
        }
        return distSum;
    }

    private int editLCSDistance(List<Link> x, List<Link> y) {
        int m = x.size(), n = y.size();
        int l[][] = new int[m+1][n+1];
        for (int i = 0; i <= m; i++) {
            for (int j = 0; j <= n; j++) {
                if (i == 0 || j == 0) {
                    l[i][j] = 0;
                } else if (x.get(i-1).equals(y.get(j-1))) {
                    l[i][j] = l[i-1][j-1] + 1;
                } else {
                    l[i][j] = Math.max(l[i-1][j], l[i][j-1]);
                }
            }
        }
        int lcs = l[m][n];
        return (m - lcs) + (n - lcs);
    }

    public List<Link> findLCS(List<Link> x, List<Link> y) {
        int m = x.size(), n = y.size();
        int l[][] = new int[m+1][n+1];
        for (int i = 0; i <= m; i++) {
            for (int j = 0; j <= n; j++) {
                if (i == 0 || j == 0) {
                    l[i][j] = 0;
                } else if (x.get(i-1).equals(y.get(j-1))) {
                    l[i][j] = l[i-1][j-1] + 1;
                } else {
                    l[i][j] = Math.max(l[i-1][j], l[i][j-1]);
                }
            }
        }

        List<Link> lcs = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 && j > 0) {
            if (x.get(i-1).equals(y.get(j-1))) {
                lcs.add(x.get(i-1));
                i--; j--;
            } else if (l[i-1][j] > l[i][j-1]) {
                i--;
            } else {
                j--;
            }
        }

        Collections.reverse(lcs);
        return lcs;
    }

    public Map<SrcDstPair, List<Link>> getSolutionLinkPath(Map<SrcDstPair, List<Link>> newLinksForPair) {
        Map<SrcDstPair, List<Link>> sdLinkPathMap = new HashMap<>();

        for (SrcDstPair key : newLinksForPair.keySet()) {
            List<Link> sPath = newLinksForPair.get(key);
            SrcDstPair sd = key;
            sdLinkPathMap.put(sd, sPath);
        }

        return sdLinkPathMap;
    }

    public Map<SrcDstPair, List<Link>> getCurrentLinkPath() {
        return sdCurrentPathMap;
    }

    private void updateSimLinkThroughputMap(Map<SrcDstPair, Path> newLinksForPair) {
        for (SrcDstPair key : newLinksForPair.keySet()) {
            Path solutionPath = newLinksForPair.get(key);
            SrcDstPair sd = key;
            incSimLinkThroughput(solutionPath, sd);
        }
    }

    private SrcDstPair getSrcDstPair(int idx) {
        return curSDList.get(idx);
    }

    private Path getSolutionPath(Map<SrcDstPair, Path> newLinksForPair, int idx) {

        SrcDstPair sd = curSDList.get(idx);
        Path solutionPath =newLinksForPair.get(sd);
        //List<Path> candidatePathList = sdAltPathListMap.get(sd);
        return solutionPath;
    }

    private double estimateMaxLinkUtilization() {
        double max = 0D;
        for (Link l : linkService.getLinks()) {
            double u = estimateUtilization(l);
            if (max < u) {
                max = u;
            }
        }
        return max;
    }

    public double estimateUtilization(Link l) {
        long throughput = simLinkThroughputMap.get(l);
        return monitorUtil.calculateUtilization(l, throughput);
    }

    private void incSimLinkThroughput(Path path, SrcDstPair sd) {
        Long txBits = sdTxBitsMap.get(sd);
        for (Link l : path.links()) {
            Long linkThroughput = simLinkThroughputMap.get(l);
            simLinkThroughputMap.put(l, linkThroughput+txBits);
        }
    }

    public Map<SrcDstPair, List<Link>>  createInitialSolution() {
        Map<SrcDstPair,List<Link>> solution=new HashMap<>();
        for (int i = 0; i <curSDList.size();i++) {
            SrcDstPair sd = curSDList.get(i);
            List<Link> path = sdCurrentPathMap.get(sd);
            Path altPathList = sdAltPathListMap.get(sd);

                if (path.equals(altPathList.links())) {
                    solution.put(sd,path);
                }

        }
        return solution;
    }

    public void analyzeSolution(ProgramGene<Double> tree) {
        initSimLinkThroughputMap();
        Map newLinksForPair=simLink( tree);
        updateSimLinkThroughputMap( newLinksForPair);
    }
}
