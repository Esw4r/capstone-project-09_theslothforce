package org.example.jsprr;

import java.io.*;
import java.nio.file.*;
import java.util.*;


public class MainJSPRRSimulation {

    public static void main(String[] args) throws Exception {
        String datasetDir = "dataset/jsprr_dataset_extended"; // path where CSVs are extracted

        // ========= 1. Load Base Stations =========
        List<BaseStationDevice> bases = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(datasetDir, "bs_nodes_extended.csv"))) {
            String header = br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] v = line.split(",");
                String id = v[0].trim();
                double cpu = Double.parseDouble(v[1]);
                double mem = Double.parseDouble(v[2]);
                double energy = Double.parseDouble(v[3]);
                double latency = Double.parseDouble(v[4]);
                BaseStationDevice bs = new BaseStationDevice(id, cpu, mem, energy, latency);
                bases.add(bs);
            }
        }

        // ========= 2. Load Network Topology =========
        NetworkTopology topo = new NetworkTopology();
        for (BaseStationDevice b : bases) topo.addNode(b);

        try (BufferedReader br = Files.newBufferedReader(Paths.get(datasetDir, "links.csv"))) {
            String header = br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] v = line.split(",");
                String id = v[0];
                String srcId = v[1];
                String dstId = v[2];
                double cap = Double.parseDouble(v[3]);
                double lat = Double.parseDouble(v[4]);
                BaseStationDevice src = findBase(bases, srcId);
                BaseStationDevice dst = findBase(bases, dstId);
                if (src != null && dst != null)
                    topo.addLink(new Link(id, src, dst, cap, lat));
            }
        }

     // ========= 3. Load Services =========
        List<ServiceModule> services = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(datasetDir, "services_extended.csv"))) {
            String header = br.readLine();
            // Handle tab- or comma-separated files safely
            String delimiter = header.contains("\t") ? "\t" : ",";
            String[] cols = header.replace("\uFEFF", "").replace("\"", "").split(delimiter);
            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                col.put(cols[i].trim().toLowerCase(), i);
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] v = line.split(delimiter);
                String id = v[col.get("service_id")].trim();
                double cpu = Double.parseDouble(v[col.get("cpu_capacity_ghz")].trim());
                double mem = Double.parseDouble(v[col.get("memory_capacity_gb")].trim());
                double cost = Double.parseDouble(v[col.get("execution_cost")].trim());
                double latency = Double.parseDouble(v[col.get("vm_fixed_latency_ms")].trim());
                ServiceModule svc = new ServiceModule(id, cpu, mem, cost, latency);
                services.add(svc);
            }
        }
        // ========= 4. Load User Requests (optional demonstration) =========
        // You can later extend this to model per-user demands, offloading decisions, etc.
        List<String> userLines = Files.readAllLines(Paths.get(datasetDir, "user_requests_extended.csv"));
        System.out.println("Loaded " + (userLines.size() - 1) + " user requests");

        // ========= 5. Generate simple communication demands =========
        List<CommDemand> commDemands = new ArrayList<>();
        Random rand = new Random(42);
        for (int i = 0; i < services.size() - 1; i++) {
            ServiceModule sA = services.get(i);
            ServiceModule sB = services.get(i + 1);
            commDemands.add(new CommDemand(sA, sB, rand.nextInt(50) + 10));
        }

        // ========= 6. Solve JSPRR (ILP + Rounding) =========
        ILPFormulation ilp = new ILPFormulation();
        double[][] x = ilp.solveLP(bases, services);

        System.out.println("LP fractional solution:");
        for (int i = 0; i < services.size(); i++) {
            System.out.println(services.get(i).getName() + ": " + Arrays.toString(x[i]));
        }

        RandomizedRounding rr = new RandomizedRounding();
        PlacementResult placement = rr.roundWithRouting(x, bases, services, commDemands, topo);

        // ========= 7. Print final placement =========
        System.out.println("\nFinal placement:");
        for (ServiceModule s : services) {
            BaseStationDevice b = placement.getBase(s);
            System.out.println(s.getName() + " -> " + (b != null ? b.getName() : "NOT PLACED"));
        }

        // ========= 8. Print link usage =========
        System.out.println("\nLink usages:");
        for (Link l : topo.getLinks()) {
            System.out.printf("%s (%s-%s): used %.2f / cap %.2f%n",
                    l.getId(), l.getA().getName(), l.getB().getName(),
                    l.getUsedBandwidth(), l.getCapacity());
        }

        // ========= 9. Evaluation Metrics =========
        System.out.println("\n=== Evaluation Metrics ===");

        // --- Service placement summary ---
        int edgeCount = 0, cloudCount = 0, unplacedCount = 0;
        for (ServiceModule s : services) {
            BaseStationDevice bs = placement.getBase(s);
            if (bs == null) {
                unplacedCount++;
            } else if (bs.getName().toLowerCase().contains("cloud")) {
                cloudCount++;
            } else {
                edgeCount++;
            }
        }

        System.out.printf("Services placed on Edge: %d%n", edgeCount);
        System.out.printf("Services placed on Cloud: %d%n", cloudCount);
        System.out.printf("Services not placed: %d%n", unplacedCount);
        System.out.println();

        // --- Resource utilization per base station ---
        System.out.println("Resource utilization per base station:");
        for (BaseStationDevice bs : bases) {
            double storageUtil = (bs.getUsedStorage() / bs.getStorage()) * 100.0;
            double computeUtil = (bs.getUsedCPU() / bs.getCpuCapacity()) * 100.0;
            double uplinkUtil = (bs.getUsedUplink() / bs.getUplinkCapacity()) * 100.0;
            double downlinkUtil = (bs.getUsedDownlink() / bs.getDownlinkCapacity()) * 100.0;

            System.out.printf("%s: Storage %.1f%%, Compute %.1f%%, Uplink %.1f%%, Downlink %.1f%%%n",
                    bs.getName(), storageUtil, computeUtil, uplinkUtil, downlinkUtil);
        }
        System.out.println();

        // --- Link utilization ---
        System.out.println("Link utilization:");
        for (Link l : topo.getLinks()) {
            double utilization = (l.getUsedBandwidth() / l.getCapacity()) * 100.0;
            System.out.printf("%s (%s-%s): %.1f%%%n",
                    l.getId(), l.getA().getName(), l.getB().getName(), utilization);
        }


        // --- System-level performance metrics ---
        System.out.println("\nSystem-level metrics:");

        // Average latency (simple estimation)
        double totalLatency = 0.0;
        int latencyCount = 0;
        for (Link l : topo.getLinks()) {
            totalLatency += l.getLatency();
            latencyCount++;
        }
        double avgLatency = latencyCount > 0 ? totalLatency / latencyCount : 0.0;

        // Edge utilization: mean of edge compute usage
        double totalEdgeUtil = 0.0;
        int edgeNodes = 0;
        for (BaseStationDevice bs : bases) {
            if (!bs.getName().toLowerCase().contains("cloud")) {
                double computeUtil = (bs.getUsedCPU() / bs.getCpuCapacity()) * 100.0;
                totalEdgeUtil += computeUtil;
                edgeNodes++;
            }
        }
        double avgEdgeUtil = edgeNodes > 0 ? totalEdgeUtil / edgeNodes : 0.0;

        // Cloud load: ratio of cloud-placed services
        double cloudLoad = (cloudCount / (double)(edgeCount + cloudCount + unplacedCount)) * 100.0;

        // Bandwidth efficiency: overall used / total link capacity
        double totalCap = 0, totalUsed = 0;
        for (Link l : topo.getLinks()) {
            totalCap += l.getCapacity();
            totalUsed += l.getUsedBandwidth();
        }
        double bwEfficiency = (totalCap > 0) ? (totalUsed / totalCap) * 100.0 : 0.0;

        // AR frame rate: inversely proportional to latency (simplified)
        double arFrameRate = (avgLatency > 0) ? Math.min(60, 1000.0 / avgLatency) : 0.0;

        // Scalability: synthetic factor based on edge utilization and bandwidth
        double scalability = 100.0 - (avgEdgeUtil * 0.2 + bwEfficiency * 0.1);

        System.out.printf("Average Latency (ms): %.2f%n", avgLatency);
        System.out.printf("Edge Utilization (%%): %.2f%n", avgEdgeUtil);
        System.out.printf("Cloud Load (%%): %.2f%n", cloudLoad);
        System.out.printf("Bandwidth Efficiency (%%): %.2f%n", bwEfficiency);
        System.out.printf("AR Frame Rate (fps): %.2f%n", arFrameRate);
        System.out.printf("Scalability (%%): %.2f%n", scalability);

    }

    private static BaseStationDevice findBase(List<BaseStationDevice> list, String id) {
        for (BaseStationDevice b : list)
            if (b.getName().equalsIgnoreCase(id))
                return b;
        return null;
    }
}