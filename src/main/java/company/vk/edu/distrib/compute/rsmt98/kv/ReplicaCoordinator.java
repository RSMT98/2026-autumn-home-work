package company.vk.edu.distrib.compute.rsmt98.kv;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ReplicaCoordinator {
    static final String REPLICA_HEADER = "X-Replica-Request";
    static final byte[] EMPTY_BODY = new byte[0];
    private static final String GET_METHOD = "GET";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final Response UNAVAILABLE =
            new Response(HttpURLConnection.HTTP_UNAVAILABLE, EMPTY_BODY);

    private final int port;
    private final FileByteDao dao;
    private final HttpClient client;

    ReplicaCoordinator(int port, FileByteDao dao, HttpClient client) {
        this.port = port;
        this.dao = dao;
        this.client = client;
    }

    Response coordinate(
            String method, String key, byte[] body, ReplicationConfig replicationConfig, int ack) {
        BlockingQueue<Response> responses =
                new ArrayBlockingQueue<>(replicationConfig.replicationFactor());
        int[] ports = replicationConfig.ports();
        int firstReplicaIndex = Math.floorMod(key.hashCode(), ports.length);
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        boolean localReplica = false;
        for (int i = 0; i < replicationConfig.replicationFactor(); ++i) {
            int replicaPort = ports[(firstReplicaIndex + i) % ports.length];
            if (replicaPort == port) {
                localReplica = true;
            } else {
                forward(replicaPort, method, encodedKey, body, responses);
            }
        }
        if (localReplica) {
            responses.add(executeLocal(method, key, body));
        }
        return collect(method, ack, replicationConfig.replicationFactor(), responses);
    }

    Response executeLocal(String method, String key, byte[] body) {
        try {
            return switch (method) {
                case GET_METHOD -> new Response(HttpURLConnection.HTTP_OK, dao.get(key));
                case "PUT" -> {
                    dao.upsert(key, body);
                    yield new Response(HttpURLConnection.HTTP_CREATED, EMPTY_BODY);
                }
                case "DELETE" -> {
                    dao.delete(key);
                    yield new Response(HttpURLConnection.HTTP_ACCEPTED, EMPTY_BODY);
                }
                default -> throw new IllegalArgumentException("Unsupported replica method");
            };
        } catch (NoSuchElementException e) {
            return new Response(HttpURLConnection.HTTP_NOT_FOUND, EMPTY_BODY);
        } catch (IOException e) {
            return UNAVAILABLE;
        }
    }

    private void forward(
            int replicaPort,
            String method,
            String encodedKey,
            byte[] body,
            BlockingQueue<Response> responses) {
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + replicaPort
                                                + "/v0/entity?id="
                                                + encodedKey))
                        .timeout(REQUEST_TIMEOUT)
                        .header(REPLICA_HEADER, "true")
                        .method(
                                method,
                                "PUT".equals(method)
                                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                                        : HttpRequest.BodyPublishers.noBody())
                        .build();
        try {
            CompletableFuture<HttpResponse<byte[]>> responseFuture =
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            responseFuture
                    .thenApply(response -> new Response(response.statusCode(), response.body()))
                    .orTimeout(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                    .exceptionally(
                            e -> {
                                responseFuture.cancel(true);
                                return UNAVAILABLE;
                            })
                    .thenAccept(responses::add);
        } catch (IllegalStateException e) {
            responses.add(UNAVAILABLE);
        }
    }

    private static Response collect(
            String method, int ack, int replicaCount, BlockingQueue<Response> responses) {
        int confirmations = 0;
        int remaining = replicaCount;
        int expectedStatus = successStatus(method);
        Response result =
                new Response(
                        GET_METHOD.equals(method)
                                ? HttpURLConnection.HTTP_NOT_FOUND
                                : expectedStatus,
                        EMPTY_BODY);
        try {
            while (confirmations < ack) {
                if (confirmations + remaining < ack) {
                    return UNAVAILABLE;
                }
                Response response = responses.take();
                --remaining;
                if (isConfirmation(method, expectedStatus, response)) {
                    ++confirmations;
                    if (response.status() == HttpURLConnection.HTTP_OK) {
                        result = response;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNAVAILABLE;
        }
        return result;
    }

    private static boolean isConfirmation(String method, int expectedStatus, Response response) {
        return response.status() == expectedStatus
                || GET_METHOD.equals(method)
                        && response.status() == HttpURLConnection.HTTP_NOT_FOUND;
    }

    private static int successStatus(String method) {
        return switch (method) {
            case GET_METHOD -> HttpURLConnection.HTTP_OK;
            case "PUT" -> HttpURLConnection.HTTP_CREATED;
            case "DELETE" -> HttpURLConnection.HTTP_ACCEPTED;
            default -> throw new IllegalArgumentException("Unsupported replica method");
        };
    }

    record Response(int status, byte[] body) {}
}
