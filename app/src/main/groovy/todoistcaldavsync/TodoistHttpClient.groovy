package todoistcaldavsync

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Small JSON/form client for the Todoist Sync API.
 *
 * This replaces the legacy http-builder dependency, which is not compatible
 * with Groovy 5. The API surface is intentionally limited to the GET and
 * form-POST requests used by the sync workflow.
 */
class TodoistHttpClient {
    private final String baseUrl
    private final String accessToken
    private final HttpClient httpClient
    private final JsonSlurper jsonSlurper = new JsonSlurper()

    TodoistHttpClient(String baseUrl, String accessToken) {
        this(baseUrl, accessToken, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build())
    }

    TodoistHttpClient(String baseUrl, String accessToken, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith('/') ? baseUrl[0..-2] : baseUrl
        this.accessToken = accessToken
        this.httpClient = httpClient
    }

    def postForm(String path, Map parameters) {
        String body = parameters.collect { key, value ->
            "${URLEncoder.encode(key.toString(), StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value.toString(), StandardCharsets.UTF_8.name())}"
        }.join('&')
        HttpRequest request = baseRequest(path)
            .header('Content-Type', 'application/x-www-form-urlencoded')
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        sendJson(request, 'POST', path)
    }

    def get(String path) {
        HttpRequest request = baseRequest(path).GET().build()
        sendJson(request, 'GET', path)
    }

    private HttpRequest.Builder baseRequest(String path) {
        HttpRequest.newBuilder(URI.create(baseUrl).resolve(path.startsWith('/') ? path : "/${path}"))
            .timeout(Duration.ofSeconds(30))
            .header('Authorization', "Bearer ${accessToken}")
            .header('Accept', 'application/json')
    }

    private def sendJson(HttpRequest request, String method, String path) {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Todoist ${method} ${path} failed with status ${response.statusCode()}: ${response.body()}")
        }
        response.body() ? jsonSlurper.parseText(response.body()) : null
    }
}
