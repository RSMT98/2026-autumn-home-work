package company.vk.edu.distrib.compute.rsmt98.kv;

import java.util.Arrays;

record ReplicationConfig(int[] ports, int replicationFactor) {
    private static final int MAX_PORT = 65535;

    static ReplicationConfig resolve(int localPort) {
        String[] configuredPorts =
                System.getProperty("CLUSTER", Integer.toString(localPort)).split(",", -1);
        int[] ports = new int[configuredPorts.length];
        for (int i = 0; i < configuredPorts.length; ++i) {
            ports[i] = Integer.parseInt(configuredPorts[i]);
        }
        Arrays.sort(ports);
        validatePorts(ports);
        if (Arrays.binarySearch(ports, localPort) < 0) {
            throw new IllegalArgumentException("Cluster must contain the local node");
        }
        int replicationFactor =
                Integer.parseInt(
                        System.getProperty("REPLICATION_FACTOR", Integer.toString(ports.length)));
        if (replicationFactor < 1 || replicationFactor > ports.length) {
            throw new IllegalArgumentException(
                    "Replication factor must be between 1 and cluster size");
        }
        return new ReplicationConfig(ports, replicationFactor);
    }

    private static void validatePorts(int... ports) {
        for (int i = 0; i < ports.length; ++i) {
            if (ports[i] < 1 || ports[i] > MAX_PORT) {
                throw new IllegalArgumentException("Node port must be between 1 and 65535");
            }
            if (i > 0 && ports[i] == ports[i - 1]) {
                throw new IllegalArgumentException("Cluster node ports must be unique");
            }
        }
    }
}
