package abc.def.dices;

import com.google.common.collect.ImmutableSet;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MonitorUtil {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private FlowRuleService flowRuleService;
    private ApplicationId appId;
    private HostService hostService;
    private DeviceService deviceService;
    private LinkService linkService;
    private TopologyService topologyService;

    public MonitorUtil() {

    }
//计算带宽利用率
    public double monitorLinkUtilization(Link l) {
        String annotateVal = l.annotations().value(Config.BANDWIDTH_KEY);
        if (annotateVal == null) {
            annotateVal = Config.DEFAULT_BANDWIDTH;
        }

        long bandwidth = stringToLong(annotateVal); //Mbps
        bandwidth = convertMbitToBit(bandwidth);
        long throughput = getDeltaTxBits(l);
        throughput = convertDeltaToSec(throughput);
        double linkUtilization = (double)throughput / (double)bandwidth;
        return linkUtilization;
    }

    public Set<FlowEntry> getFlowEntries(ConnectPoint egress) {
        ImmutableSet.Builder<FlowEntry> builder = ImmutableSet.builder();
        flowRuleService.getFlowEntries(egress.deviceId()).forEach(r -> {
            if (r.appId() == appId.id()) {
                r.treatment().allInstructions().forEach(i -> {
                    if (i.type() == Instruction.Type.OUTPUT) {
                        if (((Instructions.OutputInstruction) i).port().equals(egress.port())) {
                            builder.add(r);
                        }
                    }
                });
            }
        });

        return builder.build();
    }

    public Set<FlowEntry> getFlowEntries(SrcDstPair sd, DeviceId dId, PortNumber inPort, PortNumber outPort) {
        ImmutableSet.Builder<FlowEntry> builder = ImmutableSet.builder();
        for (FlowEntry fe : flowRuleService.getFlowEntries(dId)) {
            if (fe.appId() != appId.id()) {
                continue;
            }

            boolean matchesInPort = false, matchesOutPort = false, matchesDst = false, matchesSrc = false;
            for (Criterion cr : fe.selector().criteria()) {
                if (cr.type() == Criterion.Type.IN_PORT) {
                    if (((PortCriterion) cr).port().equals(inPort)) {
                        matchesInPort = true;
                    }
                } if (cr.type() == Criterion.Type.ETH_DST) {
                    if (((EthCriterion) cr).mac().equals(sd.dst)) {
                        matchesDst = true;
                    }
                } else if (cr.type() == Criterion.Type.ETH_SRC) {
                    if (((EthCriterion) cr).mac().equals(sd.src)) {
                        matchesSrc = true;
                    }
                }
            }
            for (Instruction i : fe.treatment().allInstructions()) {
                if (i.type() != Instruction.Type.OUTPUT) {
                    continue;
                }

                if (((Instructions.OutputInstruction) i).port().equals(outPort)) {
                    matchesOutPort = true;
                }
            }
            if (matchesInPort && matchesOutPort && matchesDst && matchesSrc) {
                builder.add(fe);
            }
        }

        return builder.build();
    }

    public long getTxBitsPerSec(SrcDstPair sd) {
        Host srcHost = hostService.getHost(HostId.hostId(sd.src));
        DeviceId srcDeviceId = srcHost.location().deviceId();
        PortNumber inPort = srcHost.location().port();
        PortStatistics portStats = deviceService.getDeltaStatisticsForPort(srcDeviceId, inPort);
        if (portStats == null) {
            return 0;
        }
        //log.error("Device {}/{} : {}", srcDeviceId, inPort, portStats.bytesReceived() * 8 / 1000 / 1000);
        return portStats.bytesReceived() * 8;
    }

    public long getTxBitsPerSecByFlowRule(SrcDstPair sd) {
        Host srcHost = hostService.getHost(HostId.hostId(sd.src));
        DeviceId srcDeviceId = srcHost.location().deviceId();

        for (FlowEntry fe : flowRuleService.getFlowEntries(srcDeviceId)) {
            SrcDstPair feSD = getSrcDstPair(fe);
            if (sd.equals(feSD) == false) {
                continue;
            }

            if (fe.life(TimeUnit.SECONDS) == 0) {
                return 0;
            }
            log.error("bits: {}, life: {}", fe.bytes()*8, fe.life(TimeUnit.SECONDS));
            return fe.bytes() * 8 / fe.life(TimeUnit.SECONDS);


        }
        return 0;
    }

    public long getDeltaTxBits(Link l) {
        DeviceId deviceId = l.src().deviceId();
        PortNumber txPortNum = l.src().port();
        PortStatistics portStats = deviceService.getDeltaStatisticsForPort(deviceId, txPortNum);
        if (portStats == null) {
            return 0;
        }
        return portStats.bytesSent() * 8;
    }

    public void logHostInfo(Host h, double deltaTxBits) {
        DeviceId devId = h.location().deviceId();
        String devName = deviceService.getDevice(devId).annotations().value("name");
        log.error("\n" +
                          "#################### host " + devName + " deltaTxBits " + deltaTxBits);

    }

    public void logHostInfo(Host src, Host dst, double deltaTxBits) {
        DeviceId srcDevId = src.location().deviceId();
        String srcDevName = deviceService.getDevice(srcDevId).annotations().value("name");
        DeviceId dstDevId = dst.location().deviceId();
        String dstDevName = deviceService.getDevice(dstDevId).annotations().value("name");

        log.error("\n" +
                          "#################### src " + srcDevName + " dst " + dstDevName + " deltaTxBits " + deltaTxBits);

    }

    public void setFlowRuleService(FlowRuleService service) {
        this.flowRuleService = service;
    }

    public void setApplicationId(ApplicationId id) {
        this.appId = id;
    }

    public void setHostService(HostService service) {
        this.hostService = service;
    }

    public void setDeviceService(DeviceService service) {
        this.deviceService = service;
    }

    public void setLinkService(LinkService service) {
        this.linkService = service;
    }

    public void setTopologyService(TopologyService service) {
        this.topologyService = service;
    }

    public SrcDstPair getSrcDstPair(FlowEntry r) {
        MacAddress src = null, dst = null;
        for (Criterion cr : r.selector().criteria()) {
            if (cr.type() == Criterion.Type.ETH_DST) {
                dst = ((EthCriterion) cr).mac();
            } else if (cr.type() == Criterion.Type.ETH_SRC) {
                src = ((EthCriterion) cr).mac();
            }
        }

        return new SrcDstPair(src, dst);
    }

    public List<Link> getCurrentPath(SrcDstPair sd) {
        List<FlowEntry> associatedFEList = findAssociatedFlowEntryList(sd);

        Host srcHost = hostService.getHost(HostId.hostId(sd.src));
        Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
        DeviceId srcId = srcHost.location().deviceId();
        DeviceId dstId = dstHost.location().deviceId();

        List<Link> linkPath = new ArrayList<>();
        DeviceId dId = srcId;
        while (dstId.equals(dId) == false) {
            FlowEntry fe = findFlowEntry(associatedFEList, dId);
            if (fe == null) {
                return null; // no valid path
            }
            PortNumber outPortNum = null;
            for (Instruction ins : fe.treatment().immediate()) {
                if (ins.type().equals(Instruction.Type.OUTPUT)) {
                    outPortNum = ((Instructions.OutputInstruction) ins).port();
                    break;
                }
            }
            Set<Link> egressLinkSet = linkService.getDeviceEgressLinks(dId);
            for (Link l : egressLinkSet) {
                if (l.src().port().equals(outPortNum)) {
                    dId = l.dst().deviceId();
                    linkPath.add(l);
                    break;
                }
            }
        }

        return linkPath;
    }

    private List<FlowEntry> findAssociatedFlowEntryList(SrcDstPair sd) {
        Set<FlowEntry> feSet = getAllCurrentFlowEntries();
        List<FlowEntry> associatedFEList = new ArrayList<>();
        for (FlowEntry fe : feSet) {
            MacAddress src = null, dst = null;
            for (Criterion cr : fe.selector().criteria()) {
                if (cr.type() == Criterion.Type.ETH_DST) {
                    dst = ((EthCriterion) cr).mac();
                } else if (cr.type() == Criterion.Type.ETH_SRC) {
                    src = ((EthCriterion) cr).mac();
                }
            }
            if (src == null || dst == null) {
                continue;
            }
            if (sd.src.equals(src) && sd.dst.equals(dst)) {
                associatedFEList.add(fe);
            }
        }
        return associatedFEList;
    }

    private FlowEntry findFlowEntry(List<FlowEntry> feList, DeviceId id) {
        for (FlowEntry fe : feList) {
            if (fe.deviceId().equals(id)) {
                return fe;
            }
        }
        return null;
    }

    public double calculateUtilization(Link l, long throughputPerSec) {
        String annotateVal = l.annotations().value(Config.BANDWIDTH_KEY);
        if (annotateVal == null) {
            annotateVal = Config.DEFAULT_BANDWIDTH;
        }

        long bandwidth = stringToLong(annotateVal); //Mbps
        bandwidth = convertMbitToBit(bandwidth);

        return (double)throughputPerSec / (double)bandwidth;
    }

    public double stringToDouble(String value) {
        return Double.valueOf(value);
    }

    public long stringToLong(String value) {
        return Long.valueOf(value);
    }

    public long convertMbitToBit(long Mbps) {
        return Mbps * 1000 * 1000;
    }

    public long convertDeltaToSec(long value) {
        return value / (Config.PROBE_INTERVAL_MS / 1000);
    }


    public Set<FlowEntry> getAllCurrentFlowEntries() {
        Set<FlowEntry> flowEntries = new LinkedHashSet<FlowEntry>();
        for (Link l : linkService.getLinks()) {
            flowEntries.addAll(getFlowEntries(l.src()));
        }
        return flowEntries;
    }

    public Set<SrcDstPair> getAllSrcDstPairs(Set<FlowEntry> flowEnties) {
        Set<SrcDstPair> sdp = new LinkedHashSet<>();
        for (FlowEntry fe : flowEnties) {
            SrcDstPair sd = getSrcDstPair(fe);
            sdp.add(sd);
        }
        return sdp;
    }

    public long getDelay(Link l) {
        String aValue = l.annotations().value(Config.LATENCY_KEY);
        if (aValue == null) {
            aValue = Config.DEFAULT_DELAY;
        }
        return stringToLong(aValue);
    }
}
