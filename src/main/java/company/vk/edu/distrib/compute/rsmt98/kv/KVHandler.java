package company.vk.edu.distrib.compute.rsmt98.kv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

final class KVHandler implements HttpHandler {
    private static final byte[] EMPTY_BODY = new byte[0];

    private final FileByteDao dao;

    KVHandler(FileByteDao dao) {
        this.dao = dao;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            int status;
            byte[] body;
            status = 200;
            body = EMPTY_BODY;
            try {
                switch (exchange.getRequestURI().getRawPath()) {
                    case "/v0/status" ->
                            status =
                                    "GET".equals(exchange.getRequestMethod())
                                            ? (dao.isAvailable() ? 200 : 503)
                                            : methodNotAllowed(exchange, "GET");
                    case "/v0/entity" -> {
                        switch (exchange.getRequestMethod()) {
                            case "GET" ->
                                    body = dao.get(readKey(exchange.getRequestURI().getRawQuery()));
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
                    }
                    default -> status = 404;
                }
            } catch (NoSuchElementException e) {
                status = 404;
            } catch (IllegalArgumentException e) {
                status = 400;
            } catch (IOException e) {
                status = 503;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            if (body.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
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
