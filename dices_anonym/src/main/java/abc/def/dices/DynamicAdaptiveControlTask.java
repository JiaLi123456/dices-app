package abc.def.dices;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.*;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;

public class DynamicAdaptiveControlTask extends TimerTask {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private boolean isExit;
    private DeviceService deviceService;
    private LinkService linkService;
    private FlowRuleService flowRuleService;
    private HostService hostService;
    private TopologyService topologyService;
    private FlowObjectiveService flowObjectiveService;
    private ApplicationId appId;
    private int flowTimeout;
    private int flowPriority;
    private boolean isCongested;
    private MonitorUtil monitorUtil;

    private int nextMonitoringCnt;

    DynamicAdaptiveControlTask() {
        isExit = false;
        isCongested = false;
        monitorUtil = new MonitorUtil();
        nextMonitoringCnt = 0;
    }

    @Override
    public void run() {
        try{
            long initTime = System.currentTimeMillis();
            monitorMaxUtil();
            if (nextMonitoringCnt > 0) {
                nextMonitoringCnt--;
                long computingTime = System.currentTimeMillis() - initTime;
                log.info("Control loop time (ms): " + computingTime);
                return;
            }

            isCongested = monitorLinks();
            if (isCongested) {
                nextMonitoringCnt = Config.NEXT_MONITORING_CNT;
                avoidCongestionBySearch();
            }
            long computingTime = System.currentTimeMillis() - initTime;
            log.info("Control loop time (ms): " + computingTime);
        } catch (Exception e) {
            StringWriter writer = new StringWriter();
            PrintWriter printer = new PrintWriter(writer);
            e.printStackTrace(printer);
            printer.flush();
            log.error("Error: " + writer.toString());
        }
    }

    private void monitorMaxUtil() {
        Iterable<Link> links = linkService.getLinks();
        double max = 0;
        for (Link l : links) {
            double lu = monitorUtil.monitorLinkUtilization(l);
            if (max < lu) {
                max = lu;
            }
        }
        log.info("Max util: " + max);
    }
//带宽大于0.8则判断阻塞
    private boolean monitorLinks() {
        Iterable<Link> links = linkService.getLinks();
        for (Link l : links) {
            double lu = monitorUtil.monitorLinkUtilization(l);
            if (lu >= Config.UTILIZATION_THRESHOLD) {
                log.warn("Congested!!! " + lu);
                return true;
            }
        }
        return false;
    }

    private void avoidCongestionBySearch() {
        SearchRunner runner = new SearchRunner(topologyService, linkService, hostService, monitorUtil);
        runner.search();
        resolveCongestion(runner);
        adjustLinkWeight(runner);
    }

    private void adjustLinkWeight(SearchRunner runner) {
        if (runner.isSolvable() == false) {
            return;
        }
        runner.analyzeSolution();

        DynamicLinkWeight linkWeight = (DynamicLinkWeight)DynamicLinkWeight.DYNAMIC_LINK_WEIGHT;
        linkWeight.lock();
        for (Link l : linkService.getLinks()) {
            long delay = monitorUtil.getDelay(l);
            double diff = diffUtilization(Config.UTILIZATION_THRESHOLD, runner.estimateUtilization(l));
            double nWeight = delay*Config.UTILIZATION_THRESHOLD / diff;
            if (nWeight < 0 || nWeight > Config.LARGE_NUM) {
                nWeight = Config.LARGE_NUM;
            }
            linkWeight.setLinkWeight(l, (int)Math.round(nWeight));
            //log.error("Link {} -> {}: util {}", l.src().deviceId(), l.dst().deviceId(), runner.estimateUtilization(l));
            //log.error("Link {} -> {}: weight {}", l.src().deviceId(), l.dst().deviceId(), (int)Math.round(nWeight));
        }
        linkWeight.unlock();
    }

    /* // first version
    private void adjustLinkWeight(SearchRunner runner) {
        if (runner.isSolvable() == false) {
            return;
        }
        runner.analyzeSolution();

        double sDegree = 1D;
        DynamicLinkWeight linkWeight = (DynamicLinkWeight)DynamicLinkWeight.DYNAMIC_LINK_WEIGHT;
        linkWeight.lock();
        for (Link l : linkService.getLinks()) {
            long delay = monitorUtil.getDelay(l);
            double u = monitorUtil.monitorLinkUtilization(l);
            double diff = diffUtilization(Config.UTILIZATION_THRESHOLD, runner.estimateUtilization(l));
            double nWeight = delay*Config.UTILIZATION_THRESHOLD / diff;
            if (u >= Config.UTILIZATION_THRESHOLD) {
                int cWeight = linkWeight.getLinkWeight(l);
                nWeight = cWeight + sDegree*nWeight;
            }
            if (nWeight < 0 || nWeight > Config.LARGE_NUM) {
                nWeight = Config.LARGE_NUM;
            }
            linkWeight.setLinkWeight(l, (int)Math.round(nWeight));
            //log.error("Link {} -> {}: weight {}", l.src().deviceId(), l.dst().deviceId(), (int)Math.round(nWeight));
        }
        linkWeight.unlock();
    }
    */

    private double diffUtilization(double x, double y) {
        if (x > y) {
            return x-y;
        }
        return Config.SMALL_NUM;
    }

    private void resolveCongestion(SearchRunner runner) {
        if (runner.isSolvable() == false) {
            return;
        }

        Map<SrcDstPair, List<Link>> curSDLinkPathMap = runner.getCurrentLinkPath();
        Map<SrcDstPair, List<Link>> solSDLinkPathMap = runner.getSolutionLinkPath();

        for (SrcDstPair sd : solSDLinkPathMap.keySet()) {
            List<Link> oLinkPath = curSDLinkPathMap.get(sd);
            List<Link> sLinkPath = solSDLinkPathMap.get(sd);
            List<Link> lcsLinkPath = runner.findLCS(oLinkPath, sLinkPath);
            addFlowEntry(sd, sLinkPath, lcsLinkPath);
            //delFlowEntryAtSrc(sd, oLinkPath, lcsLinkPath);
        }
    }

    private void delFlowEntryAtSrc(SrcDstPair sd, List<Link> origPath, List<Link> lcs) {
        Host srcHost = hostService.getHost(HostId.hostId(sd.src));
        Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
        DeviceId srcId = srcHost.location().deviceId();
        DeviceId dstId = dstHost.location().deviceId();

        Link oldLink = origPath.get(0);
        if (lcs.size() > 0 && oldLink.equals(lcs.get(0))) {
            return;
        }
        PortNumber inPort = srcHost.location().port();
        PortNumber outPort = oldLink.src().port();
        delFlowEntry(sd, oldLink.src().deviceId(), inPort, outPort);
    }

    private void delFlowEntry(SrcDstPair sd, List<Link> origPath, List<Link> lcs) {
        Host srcHost = hostService.getHost(HostId.hostId(sd.src));
        Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
        DeviceId srcId = srcHost.location().deviceId();
        DeviceId dstId = dstHost.location().deviceId();

        for (int i = 0; i < origPath.size(); i++) {
            Link oldLink = origPath.get(i);
            if (lcs.contains(oldLink)) {
                continue;
            }
            PortNumber inPort = null;
            if (i == 0) {
                inPort = srcHost.location().port();
            } else {
                inPort = origPath.get(i-1).dst().port();
            }
            PortNumber outPort = oldLink.src().port();
            delFlowEntry(sd, oldLink.src().deviceId(), inPort, outPort);
            if (oldLink.dst().deviceId().equals(dstId)) {
                delFlowEntry(sd, oldLink.dst().deviceId(), oldLink.dst().port(), dstHost.location().port());
            }
        }
    }

    private void delFlowEntry(SrcDstPair sd, DeviceId dId, PortNumber inPort, PortNumber outPort) {
        Set<FlowEntry> feSet = monitorUtil.getFlowEntries(sd, dId, inPort, outPort);
        for (FlowEntry fe : feSet) {
            flowRuleService.removeFlowRules((FlowRule) fe);
        }
        /*
        Host srcHost = hostService.getHost(HostId.hostId(sd.src));
        Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
        DeviceId srcId = srcHost.location().deviceId();
        DeviceId dstId = dstHost.location().deviceId();
        log.error("Src: " + srcId + " " + sd.src + " Dst: " + dstId + " " + sd.dst + " Del dId: " + dId + " inPort: " + inPort + " outPort: " + outPort);
        */
    }

    private void addFlowEntry(SrcDstPair sd, List<Link> newPath, List<Link> lcs) {
        Host srcHost = hostService.getHost(HostId.hostId(sd.src));
        Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
        DeviceId srcId = srcHost.location().deviceId();
        DeviceId dstId = dstHost.location().deviceId();

        for (int i = 0; i < newPath.size(); i++) {
            Link newLink = newPath.get(i);
            if (lcs.contains(newLink)) {
                continue;
            }
            PortNumber inPort = null;
            if (i == 0) {
                inPort = srcHost.location().port();
            } else {
                inPort = newPath.get(i-1).dst().port();
            }
            PortNumber outPort = newLink.src().port();
            addFlowEntry(sd, newLink.src().deviceId(), inPort, outPort);
            if (newLink.dst().deviceId().equals(dstId)) {
                addFlowEntry(sd, newLink.dst().deviceId(), newLink.dst().port(), dstHost.location().port());
            }
        }
    }

    private void addFlowEntry(SrcDstPair sd, DeviceId dId, PortNumber inPort, PortNumber outPort) {
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort)
                .build();
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchInPort(inPort)
                .matchEthSrc(sd.src)
                .matchEthDst(sd.dst)
                .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(flowPriority)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(flowTimeout)
                .add();

        flowObjectiveService.forward(dId,
                                     forwardingObjective);

        /*
        Host srcHost = hostService.getHost(HostId.hostId(sd.src));
        Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
        DeviceId srcId = srcHost.location().deviceId();
        DeviceId dstId = dstHost.location().deviceId();
        log.error("Src: " + srcId + " " + sd.src + " Dst: " + dstId + " " + sd.dst + " Add dId: " + dId + " inPort: " + inPort + " outPort: " + outPort);
        */
    }

    public void setExit(boolean isExit) {
        this.isExit = isExit;
    }

    public void setDeviceService(DeviceService service) {
        this.deviceService = service;
        monitorUtil.setDeviceService(service);
    }

    public void setLinkService(LinkService service) {
        this.linkService = service;
        monitorUtil.setLinkService(service);
    }

    public void setFlowRuleService(FlowRuleService service) {
        this.flowRuleService = service;
        monitorUtil.setFlowRuleService(service);
    }

    public void setAppId(ApplicationId id) {
        this.appId = id;
        monitorUtil.setApplicationId(id);
    }

    public void setHostService(HostService service) {
        this.hostService = service;
        monitorUtil.setHostService(service);
    }

    public void setTopologyService(TopologyService service) {
        this.topologyService = service;
        monitorUtil.setTopologyService(service);
    }

    public void setFlowTimeout(int timeout) {
        this.flowTimeout = timeout;
    }

    public void setFlowPriority(int priority) {
        this.flowPriority = priority;
    }

    public void setFlowObjectiveService(FlowObjectiveService service) {
        this.flowObjectiveService = service;
    }
}
