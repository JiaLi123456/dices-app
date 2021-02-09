package abc.def.dices;

public class Config {
    public static final double UTILIZATION_THRESHOLD = 0.8;
    public static final double UTILIZATION_MAX = 1.0;
    public static final int PROBE_INTERVAL_MS = 1000; //ms
    public static final int MAX_NUM_PATHS = 100;
    public static final String LATENCY_KEY = "latency";
    public static final String BANDWIDTH_KEY = "bandwidth";

    public static final long LARGE_NUM = 1000000;
    public static final double SMALL_NUM = 0.00001;

    public static final int NEXT_MONITORING_CNT = 3; // 2 is necessary, but +1 more for buffer

    public static final String DEFAULT_BANDWIDTH = "100"; //Mbps
    public static final String DEFAULT_DELAY = "25"; //ms
}
