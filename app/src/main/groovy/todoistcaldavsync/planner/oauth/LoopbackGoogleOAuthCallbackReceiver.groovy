package todoistcaldavsync.planner.oauth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Minimal one-shot callback receiver, intentionally restricted to an explicit IPv4 loopback bind. */
final class LoopbackGoogleOAuthCallbackReceiver implements AutoCloseable {
    private final String host
    private final int port
    private final Duration timeout
    private HttpServer server
    private CountDownLatch completed = new CountDownLatch(1)
    private String expectedState
    private String code
    private GoogleOAuthException failure

    LoopbackGoogleOAuthCallbackReceiver(String host, int port, Duration timeout = Duration.ofMinutes(5)) {
        if (host != '127.0.0.1') throw new IllegalArgumentException('OAuth callback host must be 127.0.0.1')
        if (port < 1 || port > 65535) throw new IllegalArgumentException('OAuth callback port must be 1..65535')
        this.host = host
        this.port = port
        this.timeout = timeout
    }

    synchronized URI start(String state) {
        if (!state || server != null) throw new IllegalStateException('OAuth callback receiver cannot be started')
        expectedState = state
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0)
        server.createContext('/oauth2callback', this.&handle)
        server.executor = null
        server.start()
        URI.create("http://${host}:${port}/oauth2callback")
    }

    String awaitCode() {
        try {
            if (!completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new GoogleOAuthException(GoogleOAuthErrorClass.CALLBACK_FAILURE,
                    'Google OAuth callback timed out')
            }
            if (failure != null) throw failure
            if (!code) throw new GoogleOAuthException(GoogleOAuthErrorClass.CALLBACK_FAILURE,
                'Google OAuth callback did not contain an authorization code')
            code
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new GoogleOAuthException(GoogleOAuthErrorClass.CALLBACK_FAILURE,
                'Google OAuth callback was interrupted')
        }
    }

    String getBoundHost() { server?.address?.address?.hostAddress }

    private void handle(HttpExchange exchange) throws IOException {
        int status = 400
        String responseText = 'OAuth callback rejected. You may close this window.'
        try {
            if (exchange.requestMethod != 'GET') throw callbackFailure()
            Map<String, String> query = parseQuery(exchange.requestURI.rawQuery)
            if (query.state != expectedState || query.error || !query.code) throw callbackFailure()
            code = query.code
            status = 200
            responseText = 'OAuth authorization received. You may close this window.'
        } catch (GoogleOAuthException e) {
            failure = e
        } finally {
            byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8)
            exchange.responseHeaders.set('Content-Type', 'text/plain; charset=utf-8')
            exchange.sendResponseHeaders(status, bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
            completed.countDown()
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = [:]
        (raw ?: '').split('&').findAll { it }.each { pair ->
            String[] parts = pair.split('=', 2)
            values[URLDecoder.decode(parts[0], StandardCharsets.UTF_8)] =
                URLDecoder.decode(parts.length == 2 ? parts[1] : '', StandardCharsets.UTF_8)
        }
        values
    }

    private static GoogleOAuthException callbackFailure() {
        new GoogleOAuthException(GoogleOAuthErrorClass.CALLBACK_FAILURE,
            'Google OAuth callback was rejected')
    }

    @Override synchronized void close() {
        server?.stop(0)
        server = null
    }
}
