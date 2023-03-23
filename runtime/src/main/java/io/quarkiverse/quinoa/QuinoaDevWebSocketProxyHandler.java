package io.quarkiverse.quinoa;

import static io.quarkiverse.quinoa.QuinoaDevProxyHandler.computeHostName;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.logging.Logger;

import io.quarkus.runtime.util.StringUtil;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.RoutingContext;

class QuinoaDevWebSocketProxyHandler {
    private static final Logger LOG = Logger.getLogger(QuinoaDevWebSocketProxyHandler.class);
    private final HttpClient httpClient;
    private Optional<String> configuredHost;
    private final int port;

    QuinoaDevWebSocketProxyHandler(Vertx vertx, String host, int port) {
        this.httpClient = vertx.createHttpClient();
        this.configuredHost = Optional.ofNullable(host);
        this.port = port;
    }

    public void handle(final RoutingContext ctx) {
        final HttpServerRequest request = ctx.request();
        ctx.request().pause();
        request.toWebSocket(r -> {
            if (r.succeeded()) {
                final String host = configuredHost.orElseGet(() -> computeHostName(request));
                final String forwardUri = request.uri();
                LOG.debugf("Quinoa Dev WebSocket Server Connected: %s:%s%s", host, port, forwardUri);
                final ServerWebSocket serverWs = r.result();
                final AtomicReference<WebSocket> clientWs = new AtomicReference<>();
                serverWs
                        .exceptionHandler(
                                (e) -> LOG.errorf(e, "Quinoa Dev WebSocket Server closed with error: %s", e.getMessage()))
                        .closeHandler((__) -> {
                            clientWs.getAndUpdate(w -> {
                                if (w != null && !w.isClosed()) {
                                    w.close();
                                }
                                return null;
                            });
                            LOG.debug("Quinoa Dev WebSocket Server is closed");
                        });

                // some servers use sub-protocols like Vite which must be forwarded
                final String subProtocol = serverWs.subProtocol();
                final List<String> subProtocols = new ArrayList<>(1);
                if (!StringUtil.isNullOrEmpty(subProtocol)) {
                    subProtocols.add(subProtocol);
                    LOG.debugf("Quinoa Dev WebSocket SubProtocol: %s", subProtocol);
                }

                final WebSocketConnectOptions options = new WebSocketConnectOptions()
                        .setHost(host)
                        .setPort(port)
                        .setURI(forwardUri)
                        .setHeaders(serverWs.headers())
                        .setSubProtocols(subProtocols)
                        .setAllowOriginHeader(false);
                serverWs.accept();

                httpClient.webSocket(options, clientContext -> {
                    if (clientContext.succeeded()) {
                        LOG.debugf("Quinoa Dev WebSocket Client Connected: %s:%s%s", host, port, forwardUri);
                        clientWs.set(clientContext.result());
                        // messages from NodeJS forwarded back to browser
                        clientWs.get().exceptionHandler(
                                (e) -> LOG.errorf(e, "Quinoa Dev WebSocket Client closed with error: %s", e.getMessage()))
                                .closeHandler((__) -> {
                                    LOG.debug("Quinoa Dev WebSocket Client is closed");
                                    serverWs.close();
                                }).textMessageHandler((msg) -> {
                                    LOG.debugf("Quinoa Dev WebSocket Client message: %s", msg);
                                    serverWs.writeTextMessage(msg);
                                });

                        // messages from browser forwarded to NodeJS
                        serverWs.textMessageHandler((msg) -> {
                            LOG.debugf("Quinoa Dev WebSocket Server message:  %s", msg);
                            final WebSocket w = clientWs.get();
                            if (w != null && !w.isClosed()) {
                                w.writeTextMessage(msg);
                            }
                        });
                    } else {
                        LOG.error("Quinoa Dev WebSocket Client connection failed", clientContext.cause());
                    }
                });
            } else {
                LOG.error("Error while upgrading request to WebSocket", r.cause());
            }

        });
    }
}
