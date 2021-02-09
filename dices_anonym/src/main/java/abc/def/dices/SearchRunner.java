package abc.def.dices;

import io.jenetics.Genotype;
import io.jenetics.Mutator;
import io.jenetics.Phenotype;
import io.jenetics.engine.*;
import io.jenetics.ext.SingleNodeCrossover;
import io.jenetics.ext.moea.MOEA;
import io.jenetics.ext.moea.UFTournamentSelector;
import io.jenetics.ext.moea.Vec;
import io.jenetics.ext.util.TreeNode;
import io.jenetics.prog.MathRewriteAlterer;
import io.jenetics.prog.ProgramChromosome;
import io.jenetics.prog.ProgramGene;
import io.jenetics.prog.op.*;
import io.jenetics.util.ISeq;
import io.jenetics.util.RandomRegistry;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executor;

public class SearchRunner {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private TopologyService topologyService;
    private LinkService linkService;
    private MonitorUtil monitorUtil;
    private HostService hostService;

    private Map<SrcDstPair,List<Link>> solutions;

    CongestionProblem congestionProblem;

    private Vec<double[]> kneeSolution;

    private Phenotype<ProgramGene<Double>,Vec<double[]>> solutionTree;

    private Map<SrcDstPair, List<Link>> sdLinkToDelMap;
    private Map<SrcDstPair, List<Link>> sdLinkToAddMap;

    public SearchRunner(TopologyService topologyService, LinkService linkService, HostService hostService, MonitorUtil monitorUtil) {
        this.topologyService = topologyService;
        this.linkService = linkService;
        this.hostService = hostService;
        this.monitorUtil = monitorUtil;

        this.kneeSolution = null;
    }
    // Definition of the allowed operations.
    private static final ISeq<Op<Double>> OPS =
            ISeq.of(MathOp.ADD, MathOp.SUB, MathOp.MUL, MathOp.DIV);

    // Definition of the terminals.
    private static final ISeq<Op<Double>> TMS = ISeq.of(
            Var.of("X_weight", 0),
            Var.of("Y_util", 1),
            Var.of("Z_delay", 2),
            EphemeralConst.of(() -> (double) RandomRegistry.random().nextInt(10))
    );

    private static final boolean validate(Phenotype<ProgramGene<Double>, Vec<double[]>> input) {
        Iterator<ProgramGene<Double>> out = input.genotype().gene().breadthFirstIterator();
        List<ProgramGene<Double>> copy = new ArrayList<ProgramGene<Double>>();
        while (out.hasNext())
            copy.add(out.next());
        for (ProgramGene<Double> i : copy) {
            if (i.toString().equals("div")) {
                Iterator<ProgramGene<Double>> children = i.childIterator();
                List<ProgramGene<Double>> copy2 = new ArrayList<ProgramGene<Double>>();
                while (children.hasNext())
                    copy2.add(children.next());
                //System.out.println(copy2.get(1).value().getClass().toString());

                if (copy2.get(1).eval(1.0, 2.0, 3.0).equals(0.0)) {
                    if (copy2.get(1).value().getClass().toString().equals("class io.jenetics.prog.op.MathOp")) {
                        if (copy2.get(1).eval(9.0, 8.0, 7.0).equals(0.0)) {
                            System.out.println("attention:" + new MathExpr(input.genotype().gene().toTreeNode()) + "true");
                            return false;
                        }
                    } else {
                        System.out.println("attention:" + copy2);
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static void update(
            final EvolutionResult<ProgramGene<Double>, Vec<double[]>> result) {

        for (int index = 0; index < result.population().size(); index++) {
            System.out.println(new MathExpr(result.population().get(index).genotype().gene().toTreeNode()));
        }
        System.out.println("............................................");
    }


    static final Codec<ProgramGene<Double>, ProgramGene<Double>> CODEC =
            Codec.of(
                    Genotype.of(
                            ProgramChromosome.of(
                                    3,
                                    ch -> ch.root().size() <= 15,
                                    OPS,
                                    TMS
                            )
                    ),
                    Genotype::gene
            );

    static final RetryConstraint<ProgramGene<Double>, Vec<double[]>> constraint2 = RetryConstraint.of(
            p -> validate(p),
            100
    );
    public void search() {
        long initTime = System.currentTimeMillis();


        congestionProblem = new CongestionProblem(topologyService, linkService, hostService, monitorUtil)
                .prepareSearch();
        ((CongestionProblem)congestionProblem).setSearchRunner(this);

        Problem<ProgramGene<Double>, ProgramGene<Double>, Vec<double[]>> problem = Problem.of(
                p->congestionProblem.fitness(p),
                CODEC
        );
        final Engine<ProgramGene<Double>, Vec<double[]>> engine = Engine
                .builder(problem)
                .executor((Executor) Runnable::run)
                .constraint(constraint2)
                .populationSize(50)
                .offspringFraction(0.5)
                .minimizing()
                .alterers(
                        new SingleNodeCrossover<>(0.3),
                        new Mutator<>(0.1),
                        new MathRewriteAlterer<>(0.5))
                .survivorsSelector(UFTournamentSelector.ofVec())
                //.survivorsSelector(new TournamentSelector<>(3))
                .offspringSelector(UFTournamentSelector.ofVec())
                .build();

        final EvolutionStatistics<Double, ?>
                statistics = EvolutionStatistics.ofComparable();

        final ISeq<Phenotype<ProgramGene<Double>, Vec<double[]>>> result = engine.stream()
                .limit(5)
                .peek(SearchRunner::update)
                .collect(MOEA.toParetoSet());
        //产生了一堆结果后应用knee求一个结果-tree 以及对应的hashMap

        for (Phenotype<ProgramGene<Double>, Vec<double[]>> i : result) {
            System.out.println(i);
        }

        solutionTree= getKneeSolution(result);
        final ProgramGene<Double> resultTree =solutionTree
                .genotype()
                .gene();

        System.out.println(resultTree);

        final TreeNode<Op<Double>> tree = resultTree.toTreeNode();

        MathExpr.rewrite(tree);

        System.out.println("Function:    " + new MathExpr(tree));

        Set<SrcDstPair> srcDstPairs=monitorUtil.getAllSrcDstPairs(monitorUtil.getAllCurrentFlowEntries());
        for (SrcDstPair pair:srcDstPairs){
            Map<SrcDstPair, Path> newMap=congestionProblem.simLink(resultTree);

            solutions.put(pair,newMap.get(pair).links());
        }

        kneeSolution = getKneeSolutionVectors(getKneeSolution(result));
        logKneeSolution();

        long computingTime = System.currentTimeMillis() - initTime;
        log.info("Search time (ms): " + computingTime);
    }

    private Vec<double[]> getKneeSolutionVectors(Phenotype<ProgramGene<Double>, Vec<double[]>> result) {
        return  result.fitness();
    }

    //输出kneeSolution的3个fitness值
    public void logKneeSolution() {
        if (kneeSolution == null) {
            return;
        }
        log.info("Fitness " + ": " + kneeSolution);

    }

    public Map<SrcDstPair, List<Link>> getCurrentLinkPath() {
        return ((CongestionProblem)congestionProblem).getCurrentLinkPath();
    }

    public Map<SrcDstPair, List<Link>> getSolutionLinkPath() {
        return ((CongestionProblem)congestionProblem).getSolutionLinkPath(solutions);
    }
    //LCS-最长公共子序列
    public List<Link> findLCS(List<Link> x, List<Link> y) {
        return ((CongestionProblem)congestionProblem).findLCS(x, y);
    }


    //找到与ideal点距离最近的vector的点，这里的vector是指三个fitness
    public Phenotype<ProgramGene<Double>,Vec<double[]>> getKneeSolution(ISeq<Phenotype<ProgramGene<Double>,Vec<double[]>>> results) {

       ISeq<Phenotype<ProgramGene<Double>,Vec<double[]>>> validSolutions = null;
       OUT: for (Phenotype<ProgramGene<Double>,Vec<double[]>> s : results) {
            Vec<double[]> fitness=s.fitness();
           // System.out.println(s);
            for (int i = 0; i < fitness.length(); i++) {
                if (fitness.data()[i] == Config.LARGE_NUM) {
                    break OUT;
                }
            }
            validSolutions.append(s);
        }

        Phenotype<ProgramGene<Double>,Vec<double[]>> kneeSolution = null;
        int nobj = 3;
        double minValue[] = new double[nobj];
        double maxValue[] = new double[nobj];
        for (int i = 0; i < nobj; i++) {
            minValue[i] = Double.MAX_VALUE;
            maxValue[i] = 0;
        }

        double value[] = new double[nobj];
        for (int i = 0; i < validSolutions.size(); i++) {
            for (int oIdx = 0; oIdx < nobj; oIdx++) {
                value[oIdx] = validSolutions.get(i).fitness().data()[oIdx];
                if (minValue[oIdx] > value[oIdx]) {
                    minValue[oIdx] = value[oIdx];
                }
                if (maxValue[oIdx] < value[oIdx]) {
                    maxValue[oIdx] = value[oIdx];
                }
            }
        }

        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < validSolutions.size(); i++) {
            double dist = 0D;
            for (int oIdx = 0; oIdx < nobj; oIdx++) {
                value[oIdx] = validSolutions.get(i).fitness().data()[oIdx];
                double norm = 1;
                if (maxValue[oIdx] > minValue[oIdx]) {
                    norm = (value[oIdx] - minValue[oIdx]) / (maxValue[oIdx] - minValue[oIdx]);
                }
                dist += Math.pow(norm, 2);
            }
            dist = Math.sqrt(dist);
            if (minDist > dist) {
                minDist = dist;
                kneeSolution = validSolutions.get(i);
            }
        }

        if (kneeSolution == null) {
            log.error("Knee solution == null");
            return null;
        }

        return kneeSolution;
    }

    public boolean isSolvable() {
        if (kneeSolution == null) {
            return false;
        }

        /*
        if (kneeSolution.getObjective(0) > Config.UTILIZATION_THRESHOLD) {
            return false;
        }*/

        return true;
    }

    public void analyzeSolution() {
        ((CongestionProblem)congestionProblem).analyzeSolution(solutionTree.genotype().gene());
    }

    public double estimateUtilization(Link l) {
        return ((CongestionProblem)congestionProblem).estimateUtilization(l);
    }
}
