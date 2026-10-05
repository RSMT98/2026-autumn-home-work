package company.vk.edu.distrib.compute.rsmt98.kv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

final class KVHandler implements HttpHandler {
    private static final String GET_METHOD = "GET";
    private static final byte[] EMPTY_BODY = new byte[0];

    private final FileByteDao dao;

    KVHandler(FileByteDao dao) {
        this.dao = dao;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            switch (exchange.getRequestURI().getRawPath()) {
                case "/v0/status" -> sendResponse(exchange, status(exchange), EMPTY_BODY);
                case "/v0/entity" -> handleEntity(exchange);
                default -> sendResponse(exchange, 404, EMPTY_BODY);
            }
        }
    }

    private int status(HttpExchange exchange) {
        if (!GET_METHOD.equals(exchange.getRequestMethod())) {
            return methodNotAllowed(exchange, GET_METHOD);
        }
        return dao.isAvailable() ? 200 : 503;
    }

    private void handleEntity(HttpExchange exchange) throws IOException {
        int status = 200;
        byte[] body = EMPTY_BODY;
        try {
            switch (exchange.getRequestMethod()) {
                case GET_METHOD -> body = dao.get(readKey(exchange.getRequestURI().getRawQuery()));
                case "PUT" -> {
                    dao.upsert(
                            readKey(exchange.getRequestURI().getRawQuery()),
                            exchange.getRequestBody().readAllBytes());
                    status = 201;
                }
                case "DELETE" -> {
                    dao.delete(readKey(exchange.getRequestURI().getRawQuery()));
                    status = 202;
                }
                default -> status = methodNotAllowed(exchange, "GET, PUT, DELETE");
            }
        } catch (NoSuchElementException e) {
            status = 404;
        } catch (IllegalArgumentException e) {
            status = 400;
        } catch (IOException e) {
            status = 503;
        }
        sendResponse(exchange, status, body);
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
        if (query == null) {
            throw new IllegalArgumentException("Missing key");
        }
        int offset = 0;
        while (offset < query.length()) {
            int end = query.indexOf('&', offset);
            if (end == -1) {
                end = query.length();
            }
            if (query.startsWith("id=", offset)) {
                String key =
                        URLDecoder.decode(query.substring(offset + 3, end), StandardCharsets.UTF_8);
                if (key.isEmpty()) {
                    throw new IllegalArgumentException("Key must be non-empty");
                }
                return key;
            }
            offset = end + 1;
        }
        throw new IllegalArgumentException("Missing key");
    }

    private static int methodNotAllowed(HttpExchange exchange, String methods) {
        exchange.getResponseHeaders().set("Allow", methods);
        return 405;
    }
}
