package org.graalvm.argo.dataset.multilang;

import java.util.*;

public class BenchmarkDuration {

    private static final Map<FunctionLanguage, TreeMap<Integer, List<String>>> benchmarks = new HashMap<>();

    static {
        benchmarks.put(FunctionLanguage.JAVA, new TreeMap<>());
        benchmarks.put(FunctionLanguage.JAVASCRIPT, new TreeMap<>());
        benchmarks.put(FunctionLanguage.PYTHON, new TreeMap<>());

        addBenchmark(FunctionLanguage.JAVA, "jvhw", 8);
        addBenchmark(FunctionLanguage.JAVA, "jvfh", 11);
        addBenchmark(FunctionLanguage.JAVA, "jvcl", 187);
        addBenchmark(FunctionLanguage.JAVA, "jvhr", 10);
        addBenchmark(FunctionLanguage.JAVA, "jvvp", 2914);

        addBenchmark(FunctionLanguage.PYTHON, "pyhw", 6);
        addBenchmark(FunctionLanguage.PYTHON, "pyms", 7);
        addBenchmark(FunctionLanguage.PYTHON, "pybf", 7);
        addBenchmark(FunctionLanguage.PYTHON, "pypr", 7);
        addBenchmark(FunctionLanguage.PYTHON, "pydn", 7);
        addBenchmark(FunctionLanguage.PYTHON, "pydh", 10);
        addBenchmark(FunctionLanguage.PYTHON, "pyco", 69);
        addBenchmark(FunctionLanguage.PYTHON, "pyth", 21);
        addBenchmark(FunctionLanguage.PYTHON, "pyvp", 809);
        addBenchmark(FunctionLanguage.PYTHON, "pyup", 16);

        addBenchmark(FunctionLanguage.JAVASCRIPT, "jshw", 5);
        addBenchmark(FunctionLanguage.JAVASCRIPT, "jsdh", 5);
        addBenchmark(FunctionLanguage.JAVASCRIPT, "jsth", 22);
        addBenchmark(FunctionLanguage.JAVASCRIPT, "jsup", 9);
    }

    private static void addBenchmark(FunctionLanguage language, String name, int duration) {
        benchmarks.get(language).computeIfAbsent(duration, k -> new ArrayList<>()).add(name);
    }

    static String getBenchmark(FunctionLanguage language, int functionDuration, String functionHash) {
        Integer floor = benchmarks.get(language).floorKey(functionDuration);
        Integer ceiling = benchmarks.get(language).ceilingKey(functionDuration);

        Integer closestDuration = (floor == null) ? ceiling :
                                (ceiling == null) ? floor :
                                        (Math.abs(functionDuration - floor) <= Math.abs(functionDuration - ceiling) ? floor : ceiling);

        if (closestDuration == null) {
            throw new IllegalArgumentException("No valid benchmark found for the specified duration: " + functionDuration);
        }

        List<String> candidates = benchmarks.get(language).get(closestDuration);
        
        // Deterministically pick the candidate using the function's string hash
        int index = Math.abs(functionHash.hashCode()) % candidates.size();
        return candidates.get(index);
    }
}
