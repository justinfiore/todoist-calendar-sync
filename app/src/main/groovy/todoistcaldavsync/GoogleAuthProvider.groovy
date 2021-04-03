package todoistcaldavsync

import org.apache.log4j.*
import org.apache.commons.lang3.*
import groovy.util.logging.*
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.DateTime;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.CredentialRefreshListener
import com.google.api.services.calendar.model.Event
import groovy.yaml.YamlSlurper
import java.util.HashSet
import groovy.cli.picocli.CliBuilder

import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

@Log4j
class GoogleAuthProvider {

    public static void showHelp(cli) {
        cli.usage()
    }
    
    public static void main(String[] args) {
        def cli = new CliBuilder(usage: 'GoogleAuthProvider.groovy -f configFile -l log4j.groovy')
        cli.setFooter("Retrieves and stores a OAuth2 credentials from Google for the configured Google users in the YAML file ")
        cli.f(args: 1, argName: "configFile", "Specify the YAML config file to use")
        cli.l(args: 1, argName: "log4j.groovy", "the Log4j Configuration groovy file")
        cli.h(args: 0, "Show the help")
        def options = cli.parse(args)

        if(options.h) {
            showHelp(cli)
            System.exit(0);
        }

        if (!options.f) {
            throw new IllegalArgumentException("You must specify the config file");
        }

        if (!options.l) {
            throw new IllegalArgumentException("You must specify the log4j file");
        }

        def logConfig = new ConfigSlurper().parse(new File(options.l).toURL())
        PropertyConfigurator.configure(logConfig.toProperties())

        log.info("----------------------------------------------------------------")


        def configFile = new File(options.f);

        def slurper = new YamlSlurper();
		def config = slurper.parse(configFile);
        def googleUsernames = collectGoogleUsernames(config)

        log.info("Found the following Google Usernames in configuration: ${googleUsernames}")

        def googleAuthProvider = new GoogleAuthProvider(configFile.getParentFile())

        googleUsernames.each { username ->
            log.info("Retrieving Google OAuth2 Credentials for username: ${username} ...")
            googleAuthProvider.getCredential(username)
        }
        log.info("Finished retrieving and storing Google OAuth2 Credentials for all usernames")

    }

    static def collectGoogleUsernames(config) {
        def defaultAuthProps = config.caldav?.default?.auth
        def googleUsernames = new HashSet<String>()
        if(defaultAuthProps && defaultAuthProps.scheme) {
            def defaultScheme = AuthScheme.valueOf(defaultAuthProps.scheme)
            if(defaultScheme == AuthScheme.GOOGLE_OAUTH2) {
                def username = defaultAuthProps?.google?.username
                if(StringUtils.isNotBlank(username)){
                    googleUsernames.add(username)
                }
            }
        }

        config.caldav.calendars.each { calConfig ->
            if(calConfig.auth && AuthScheme.valueOf(calConfig.auth.scheme) == AuthScheme.GOOGLE_OAUTH2) {
                def username = calConfig.auth.google?.username
                if(StringUtils.isNotBlank(username)){
                    googleUsernames.add(username)
                }
            }
        }
        return googleUsernames
    }

    File confDir = null

    /** Directory to store user credentials for this application. */
    private java.io.File DATA_STORE_DIR = null;

    /** Global instance of the {@link FileDataStoreFactory}. */
    private FileDataStoreFactory DATA_STORE_FACTORY;

    /** Global instance of the JSON factory. */
    private final JsonFactory JSON_FACTORY =
            JacksonFactory.getDefaultInstance();

    /** Global instance of the HTTP transport. */
    private  HttpTransport HTTP_TRANSPORT;

    private final List<String> SCOPES =
            Arrays.asList(CalendarScopes.CALENDAR_EVENTS, CalendarScopes.CALENDAR);

    def GoogleAuthProvider(File confDir) {
        this.confDir = confDir
        this.DATA_STORE_DIR = confDir

        HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        DATA_STORE_FACTORY = new FileDataStoreFactory(DATA_STORE_DIR);
   
    }

    def GoogleClientSecrets readClientSecrets() {
        InputStream is = new FileInputStream(new File(confDir, "client_secret.json"));
        GoogleClientSecrets clientSecrets =
                GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(is));
        //log.info("Read clientSecrets: $clientSecrets")
        return clientSecrets
    }

    /**
     * Build and return an OAuth2 Credential for the specified Google Username
     * @return an authorized OAuth2 Credential
     * @throws IOException
     */
    public Credential getCredential(String googleUsername) throws IOException {
        DataStore<StoredCredential> dataStore = DATA_STORE_FACTORY.getDataStore("user")
        //log.info("$googleUsername = ${dataStore.get(googleUsername)}")
        def storedCredential = dataStore.get(googleUsername)

        Credential credential = null;
        if(storedCredential == null) {
            credential = authorize()
            dataStore.set(googleUsername, new StoredCredential(credential))
        }
        else {
            credential = storedCredentialToCredential(storedCredential)
        }

        return credential
    }

    private Credential authorize() throws IOException {
        // Load client secrets.

        GoogleClientSecrets clientSecrets = readClientSecrets()

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(
                        HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                        .setDataStoreFactory(DATA_STORE_FACTORY)
                        .setAccessType("offline")
                        .build();
        Credential credential = new AuthorizationCodeInstalledApp(
                flow, new LocalServerReceiver()).authorize("user");
        log.info(
                "Credentials saved to " + DATA_STORE_DIR.getAbsolutePath());
        return credential;
    }

    private Credential storedCredentialToCredential(StoredCredential storedCredential) {
        JsonFactory jsonFactory = new JacksonFactory();
        GoogleClientSecrets clientSecrets = readClientSecrets()
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(HTTP_TRANSPORT)
                .setJsonFactory(jsonFactory)
                .setClientSecrets(clientSecrets)
                //.addRefreshListener(new LoggingCredentialRefreshListener())
                .build();
        credential.setRefreshToken(storedCredential.getRefreshToken())
                .setAccessToken(storedCredential.getAccessToken())
                .setExpirationTimeMilliseconds(storedCredential.getExpirationTimeMilliseconds());
        log.debug("Credential will expire at: ${new Date(storedCredential.getExpirationTimeMilliseconds())}")
        credential.refreshToken()
        log.debug("Refreshed token now expires at: ${new Date(credential.getExpirationTimeMilliseconds())}")
        return credential
    }

}