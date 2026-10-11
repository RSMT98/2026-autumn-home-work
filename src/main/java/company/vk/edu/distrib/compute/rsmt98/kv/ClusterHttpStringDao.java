package company.vk.edu.distrib.compute.rsmt98.kv;

import company.vk.edu.distrib.compute.Dao;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ClusterHttpStringDao implements Dao<String> {
    private static final int MAX_PORT = 65535;
    private final List<HttpStringDao> nodes;

    ClusterHttpStringDao(int... ports) {
        int[] sortedPorts = ports.clone();
        if (sortedPorts.length == 0) {
            throw new IllegalArgumentException("Cluster must contain at least one node");
        }

        Arrays.sort(sortedPorts);
        for (int i = 0; i < sortedPorts.length; ++i) {
            if (sortedPorts[i] < 1 || sortedPorts[i] > MAX_PORT) {
                throw new IllegalArgumentException("Node port must be between 1 and 65535");
            }
            if (i > 0 && sortedPorts[i] == sortedPorts[i - 1]) {
                throw new IllegalArgumentException("Cluster node ports must be unique");
            }
        }

        nodes = new ArrayList<>(sortedPorts.length);
        try {
            for (int port : sortedPorts) {
                nodes.add(new HttpStringDao(port));
            }
        } catch (RuntimeException e) {
            try {
                close();
            } catch (RuntimeException ee) {
                e.addSuppressed(ee);
            }
            throw e;
        }
    }

    @Override
    public String get(String key) throws IOException {
        return owner(key).get(key);
    }

    @Override
    public void upsert(String key, String value) throws IOException {
        owner(key).upsert(key, value);
    }

    @Override
    public void delete(String key) throws IOException {
        owner(key).delete(key);
    }

    @Override
    public void close() {
        RuntimeException e = null;
        for (HttpStringDao node : nodes) {
            try {
                node.close();
            } catch (RuntimeException ee) {
                if (e == null) {
                    e = ee;
                } else if (e != ee) {
                    e.addSuppressed(ee);
                }
            }
        }
        if (e != null) {
            throw e;
        }
    }

    private HttpStringDao owner(String key) {
        return nodes.get(Math.floorMod(key.hashCode(), nodes.size()));
    }
}
