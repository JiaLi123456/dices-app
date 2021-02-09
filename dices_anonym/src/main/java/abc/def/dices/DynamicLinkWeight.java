package abc.def.dices;

import org.onlab.graph.ScalarWeight;
import org.onlab.graph.Weight;
import org.onosproject.net.Link;
import org.onosproject.net.topology.LinkWeigher;
import org.onosproject.net.topology.TopologyEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class DynamicLinkWeight  implements LinkWeigher {
    public static final LinkWeigher DYNAMIC_LINK_WEIGHT = new DynamicLinkWeight();
    private final Lock weightLock = new ReentrantLock();
    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final ScalarWeight ZERO = new ScalarWeight(0D);
    private static final ScalarWeight ONE = new ScalarWeight(1D);


    private Map<Link, ScalarWeight> edgeCostMap;

    public DynamicLinkWeight() {
        edgeCostMap = new HashMap<>();
    }

    public Weight weight(TopologyEdge edge) {
        weightLock.lock();
        Weight weight = edgeCostMap.get(edge.link());
        if (weight == null) {
            String annotateVal = edge.link().annotations().value(Config.LATENCY_KEY);
            if (annotateVal == null) {
                edgeCostMap.put(edge.link(), new ScalarWeight(Integer.valueOf(Config.DEFAULT_DELAY)));
                //edgeCostMap.put(edge.link(), ONE);
            } else {
                edgeCostMap.put(edge.link(), new ScalarWeight(Integer.valueOf(annotateVal)));
            }
            weightLock.unlock();
            return edgeCostMap.get(edge.link());
        }
        weightLock.unlock();
        return weight;
    }

    public Weight getInitialWeight() {
        return ZERO;
    }

    public Weight getNonViableWeight() {
        return ScalarWeight.NON_VIABLE_WEIGHT;
    }

    public int getLinkWeight(Link l) {
        ScalarWeight weight = edgeCostMap.get(l);
        return (int)weight.value();
    }

    public void setLinkWeight(Link l, int weight) {
        edgeCostMap.put(l, new ScalarWeight(weight));
    }

    public void lock() {
        weightLock.lock();
    }

    public void unlock() {
        weightLock.unlock();
    }
}
