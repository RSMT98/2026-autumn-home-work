package company.vk.edu.distrib.compute.rsmt98.kv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

final class KVHandler implements HttpHandler {
    private static final String GET_METHOD = "GET";
    private static final String KEY_QUERY_PREFIX = "id=";
    private static final int MAX_VALUE_BYTES = 1024 * 1024;

    private final int port;
    private final FileByteDao dao;
    private final ReplicaCoordinator coordinator;

    KVHandler(int port, FileByteDao dao, ReplicaCoordinator coordinator) {
        this.port = port;
        this.dao = dao;
        this.coordinator = coordinator;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            switch (exchange.getRequestURI().getRawPath()) {
                case "/v0/status" ->
                        sendResponse(exchange, status(exchange), ReplicaCoordinator.EMPTY_BODY);
                case "/v0/entity" -> handleEntity(exchange);
                default ->
                        sendResponse(
                                exchange,
                                HttpURLConnection.HTTP_NOT_FOUND,
                                ReplicaCoordinator.EMPTY_BODY);
            }
        }
    }

    private int status(HttpExchange exchange) {
        if (!GET_METHOD.equals(exchange.getRequestMethod())) {
            return methodNotAllowed(exchange, GET_METHOD);
        }
        return dao.isAvailable() ? HttpURLConnection.HTTP_OK : HttpURLConnection.HTTP_UNAVAILABLE;
    }

    private void handleEntity(HttpExchange exchange) throws IOException {
        try (InputStream requestBody = exchange.getRequestBody()) {
            ReplicaCoordinator.Response response;
            try {
                response =
                        switch (exchange.getRequestMethod()) {
                            case GET_METHOD, "PUT", "DELETE" ->
                                    processEntityRequest(exchange, requestBody);
                            default ->
                                    new ReplicaCoordinator.Response(
                                            methodNotAllowed(exchange, "GET, PUT, DELETE"),
                                            ReplicaCoordinator.EMPTY_BODY);
                        };
            } catch (IllegalArgumentException e) {
                response =
                        new ReplicaCoordinator.Response(
                                HttpURLConnection.HTTP_BAD_REQUEST, ReplicaCoordinator.EMPTY_BODY);
            } catch (IOException e) {
                response =
                        new ReplicaCoordinator.Response(
                                HttpURLConnection.HTTP_UNAVAILABLE, ReplicaCoordinator.EMPTY_BODY);
            }
            sendResponse(exchange, response.status(), response.body());
        }
    }

    private ReplicaCoordinator.Response processEntityRequest(
            HttpExchange exchange, InputStream requestBody) throws IOException {
        String key = readKey(exchange.getRequestURI().getRawQuery());
        boolean internal =
                "true"
                        .equals(
                                exchange.getRequestHeaders()
                                        .getFirst(ReplicaCoordinator.REPLICA_HEADER));
        ReplicationConfig replicationConfig = internal ? null : ReplicationConfig.resolve(port);
        int ack =
                readAck(
                        exchange.getRequestURI().getRawQuery(),
                        replicationConfig == null ? 1 : replicationConfig.replicationFactor());
        byte[] body = ReplicaCoordinator.EMPTY_BODY;
        if ("PUT".equals(exchange.getRequestMethod())) {
            body = requestBody.readNBytes(MAX_VALUE_BYTES);
            if (body.length == MAX_VALUE_BYTES && requestBody.read() != -1) {
                return new ReplicaCoordinator.Response(
                        HttpURLConnection.HTTP_ENTITY_TOO_LARGE, ReplicaCoordinator.EMPTY_BODY);
            }
        }
        return replicationConfig == null
                ? coordinator.executeLocal(exchange.getRequestMethod(), key, body)
                : coordinator.coordinate(
                        exchange.getRequestMethod(), key, body, replicationConfig, ack);
    }

    private static void sendResponse(HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static String readKey(@Nullable String query) {
        String key = readParameter(query, KEY_QUERY_PREFIX);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key must be non-empty");
        }
        return key;
    }

    private static int readAck(@Nullable String query, int replicationFactor) {
        String value = readParameter(query, "ack=");
        int ack = value == null ? 1 : Integer.parseInt(value);
        if (ack < 1 || ack > replicationFactor) {
            throw new IllegalArgumentException("Ack must be between 1 and replication factor");
        }
        return ack;
    }

    @Nullable
    private static String readParameter(@Nullable String query, String prefix) {
        if (query == null) {
            return null;
        }
        int offset = 0;
        while (offset < query.length()) {
            int end = query.indexOf('&', offset);
            if (end == -1) {
                end = query.length();
            }
            if (query.startsWith(prefix, offset)) {
                return URLDecoder.decode(
                        query.substring(offset + prefix.length(), end), StandardCharsets.UTF_8);
            }
            if (end - offset == prefix.length() - 1
                    && query.regionMatches(offset, prefix, 0, end - offset)) {
                return "";
            }
            offset = end + 1;
        }
        return null;
    }

    private static int methodNotAllowed(HttpExchange exchange, String methods) {
        exchange.getResponseHeaders().set("Allow", methods);
        return HttpURLConnection.HTTP_BAD_METHOD;
    }
}
