package company.vk.edu.distrib.compute.rsmt98.kv;

import com.sun.net.httpserver.HttpServer;

import company.vk.edu.distrib.compute.kv.KVService;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class HttpKVService implements KVService {
    private static final int SINGLE_THREAD_COUNT = 1;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("rsmt98");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create KV storage directory", e);
        }
    }

    private final int port;
    private final int threads;
    private final Lock lock = new ReentrantLock();
    private boolean startCalled;
    private boolean stopCalled;
    @Nullable private HttpServer server;
    @Nullable private ExecutorService executor;
    @Nullable private FileByteDao dao;
    @Nullable private HttpClient client;

    public HttpKVService(int port, int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        this.port = port;
        this.threads = threads;
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (startCalled) {
                throw new IllegalStateException("Service can only be started once");
            }
            startCalled = true;
            try {
                dao = new FileByteDao(DATA_DIR.resolve(Integer.toString(port)));
                client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
                server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
                if (threads > SINGLE_THREAD_COUNT) {
                    executor = Executors.newFixedThreadPool(threads);
                    server.setExecutor(executor);
                }
                server.createContext(
                        "/", new KVHandler(port, dao, new ReplicaCoordinator(port, dao, client)));
                server.start();
            } catch (IOException | RuntimeException e) {
                try {
                    stop();
                } catch (RuntimeException | Error ee) {
                    e.addSuppressed(ee);
                }
                throw new IllegalStateException("Cannot start KV service on port " + port, e);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            if (!startCalled || stopCalled) {
                return;
            }
            stopCalled = true;
            FileByteDao storage = dao;
            ExecutorService workers = executor;
            HttpClient httpClient = client;
            try (storage;
                    httpClient;
                    workers) {
                HttpServer httpServer = server;
                if (httpServer != null) {
                    httpServer.stop(1);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
