package todoistcaldavsync


import org.apache.http.HttpRequestInterceptor
import org.apache.http.HttpRequest;
import org.apache.http.protocol.HttpContext;
import org.apache.http.HttpException;

import com.google.api.client.auth.oauth2.Credential;

import groovy.util.logging.*

@Log4j
class GoogleOAuthRequestInterceptor implements HttpRequestInterceptor {

    Credential credential = null

    GoogleOAuthRequestInterceptor(Credential credential) {
        this.credential = credential
    }

    @Override
    public void process(HttpRequest request, HttpContext context) throws HttpException, IOException {
        if(System.currentTimeMillis() > credential.getExpirationTimeMilliseconds()) {
            log.info("Refreshing OAuth Token ...")
            def tokenRefreshedSuccessfully = credential.refreshToken();
            if(tokenRefreshedSuccessfully) {
                log.info("Successfully refreshed OAuth Token.")
            }
            else {
                log.error("Could not refresh the OAuth Token")
            }
        }
        def accessToken = credential.getAccessToken()
        log.trace("Set header: Authorization: Bearer " + accessToken)
        request.setHeader("Authorization", "Bearer " + accessToken)
    }


}