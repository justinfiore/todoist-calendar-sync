package todoistcaldavsync

import org.apache.commons.codec.binary.Base32
import java.time.*;
import org.apache.commons.lang3.*
import java.text.ParseException;
import org.apache.log4j.*
import groovy.util.logging.*
import groovyx.net.http.RESTClient
import groovy.xml.StreamingMarkupBuilder
import groovy.xml.XmlUtil
import static groovyx.net.http.ContentType.*
import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import groovy.yaml.YamlBuilder
import groovy.json.JsonOutput
import org.apache.commons.io.*
import org.apache.commons.io.filefilter.*
import org.apache.commons.lang.time.DurationFormatUtils
import java.text.SimpleDateFormat
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.Duration;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Version;
import net.fortuna.ical4j.model.property.Priority;
import net.fortuna.ical4j.model.property.Color;
import net.fortuna.ical4j.model.property.CalScale;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.pool.PoolStats
import com.github.caldav4j.CalDAVCollection;
import com.github.caldav4j.util.ICalendarUtils;
import com.github.caldav4j.CalDAVConstants;
import com.github.caldav4j.exceptions.ResourceNotFoundException
import java.util.concurrent.TimeUnit
import groovy.cli.picocli.CliBuilder
import com.google.api.client.auth.oauth2.Credential;


@Log4j
class TodoistCalDavSync {

    public static void showHelp(cli) {
        cli.usage()
    }
    
    public static void main(String[] args) {
        def cli = new CliBuilder(usage: 'TodoistCalDavSync.groovy -f configFile -l log4j.groovy')
        cli.setFooter("Syncs Todoist Events with CalDav Calendars")
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
        def stateFile = new File(configFile.getParentFile(), configFile.getName().replace(".conf", ".state"))

        def syncer = new TodoistCalDavSync(configFile, stateFile)
        syncer.syncLoop()

    }

    static def todoistApiBaseUrl = "https://api.todoist.com/sync/v9"

    def currentTimeZoneOffset() {
        return String.format("%tz", Instant.now().atZone(ZoneId.systemDefault()));
    }

    def dateTimeFormatWithNoTimeZone = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    def dateFormatWithNoTimeZone = new SimpleDateFormat("yyyy-MM-dd")

    def getTimeZoneOffsetAtDateTime(dateTimeStrWithoutZone) {
        def dateTime = null
        try {
            dateTime = dateTimeFormatWithNoTimeZone.parse(dateTimeStrWithoutZone);
        } catch(ParseException e) {
            dateTime = dateFormatWithNoTimeZone.parse(dateTimeStrWithoutZone)
        }
        if(dateTime == null) {
            return ""
        }
        return String.format("%tz", Instant.ofEpochMilli(dateTime.getTime()).atZone(ZoneId.systemDefault()));
    }

    

    def config = [:]
    def state = [:]
    def stateFile = null
    def configFile = null
    def dryRun = false
    def DRY_RUN_PREFIX = "Dry Run:"

    def caldavHttpClientsByCalendar = [:]
    def urlsByCalendar = [:]
    def connectionManagers = [:]
    def prefixesByCalendar = [:]
    def poolThresholdPercent = 50  // Reset pool when usage exceeds 50%
    def lastPoolResetTime = [:]     // Track when pools were last reset

    def TodoistCalDavSync(configFile, stateFile) {
        def slurper = new YamlSlurper();
		def config = slurper.parse(configFile);
		this.config = config

        if (config.dryRun) {
            this.dryRun = config.dryRun
            log.info("$DRY_RUN_PREFIX ${dryRun}")
        }

        if (stateFile.exists()) {
		
			try {
				state = slurper.parse(stateFile).state;
			} catch(Exception e) {
				log.error("Couldn't parse state file, proceeding with empty state")
				state = [:]
			}
        }

        this.configFile = configFile
        this.stateFile = stateFile
        configureCalendarUrls() 
        configureCalDavHttpClients()

    }

    def SUPPORTED_AUTH_SCHEMES = [AuthScheme.BASIC, AuthScheme.GOOGLE_OAUTH2]

    def configureCalendarUrls() {
         config.caldav.calendars.each { calConfig ->
            def calUrl = calConfig.url
            if(StringUtils.isBlank(calUrl)) {
                throw new IllegalArgumentException("url property of a calendar must not be empty")
            }

            def calName = calConfig.name
            if(StringUtils.isBlank(calName)) {
                throw new IllegalArgumentException("name property of a calendar must not be empty")
            }
            urlsByCalendar[calName] = calUrl
            if(calConfig.prefix) {
                prefixesByCalendar[calName] = calConfig.prefix
            }
         }
    }

    def destroyHttpClients() {
        connectionManagers.each { calendarName, cm ->
            log.trace("Shutting down ConnectionManager for calendar: $calendarName ...")
            cm.shutdown()
            log.trace("Shutting down ConnectionManager for calendar: $calendarName successful.")
        }
        caldavHttpClientsByCalendar.each { calendarName, httpClient ->
            log.trace("Shutting down HttpClient for calendar: $calendarName ...")
            httpClient.close()
            log.trace("Shutting down HttpClient for calendar: $calendarName successful.")
        }
        connectionManagers = [:]
        caldavHttpClientsByCalendar = [:]
    }

    GoogleAuthProvider googleAuthProvider = null

    def configureCalDavHttpClients() {

        if(!caldavHttpClientsByCalendar.isEmpty()) {
            destroyHttpClients()
        }

        def defaultAuthProps = config.caldav?.default?.auth

        config.caldav.calendars.each { calConfig ->
            def calAuthProps = calConfig.auth ?: defaultAuthProps
            def calUrl = calConfig.url
            if(StringUtils.isBlank(calUrl)) {
                throw new IllegalArgumentException("url property of a calendar must not be empty")
            }

            def calName = calConfig.name
            if(StringUtils.isBlank(calName)) {
                throw new IllegalArgumentException("name property of a calendar must not be empty")
            }
            def httpClientBuilder = HttpClients.custom()

            if(calAuthProps) {
                def scheme = AuthScheme.valueOf(calAuthProps.scheme)
                if(!SUPPORTED_AUTH_SCHEMES.contains(scheme)) {
                    throw new IllegalArgumentException("Invalid scheme: $scheme. Valid Schemes: ${SUPPORTED_AUTH_SCHEMES}")
                }

                if(scheme == AuthScheme.BASIC) {
                    def password = StringUtils.defaultString(calAuthProps.basicAuth?.password, System.getenv("CALDAV_AUTH_BASICAUTH_PASSWORD"))
                    def username = calAuthProps.basicAuth?.username
                    if(StringUtils.isBlank(password)) {
                        throw new IllegalArgumentException("Invalid password. It is blank")
                    }
                    if(StringUtils.isBlank(username)) {
                        throw new IllegalArgumentException("Invalid username. It is blank")
                    }
                    BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                    def url = new URL(calUrl)
                    credsProvider.setCredentials(
                            new AuthScope(url.getHost(), url.getPort()),
                            new UsernamePasswordCredentials(username, password))
                    httpClientBuilder.setDefaultCredentialsProvider(credsProvider)
                    log.info("Using username: $username with calendar name: $calName")
                }
                if(scheme == AuthScheme.GOOGLE_OAUTH2) {
                    if(googleAuthProvider == null) {
                        googleAuthProvider = new GoogleAuthProvider(configFile.getParentFile())
                    }
                    def username = calAuthProps.google?.username
                    log.debug("Configuring Google OAuth2 Authentication Scheme for username: $username")
                    if(StringUtils.isBlank(username)) {
                        throw new IllegalArgumentException("Invalid username. It is blank")
                    }
                    Credential credential = googleAuthProvider.getCredential(username)
                    def googleOAuthInterceptor = new GoogleOAuthRequestInterceptor(credential)
                    httpClientBuilder.addInterceptorFirst(googleOAuthInterceptor)
                }
            }
            def timeout = 5
            RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeout * 1000)
                .setConnectionRequestTimeout(timeout * 1000)
                .setSocketTimeout(timeout * 1000).build();
            PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
            
            int maxConnections = config.maxConnectionsPerHttpClient ?: 10
            cm.setMaxTotal(maxConnections);
            cm.setDefaultMaxPerRoute(maxConnections);
            connectionManagers[calName] = cm
            httpClientBuilder.setConnectionManager(cm)
            httpClientBuilder.setDefaultRequestConfig(requestConfig)
            def httpClient = httpClientBuilder.build()
            caldavHttpClientsByCalendar.put(calName, httpClient)
        }
        log.info("Configured Calendars: ${caldavHttpClientsByCalendar.keySet()}")
    }

    def saveStateFile(state) {
        if(dryRun) {
            log.info("$DRY_RUN_PREFIX Would have written to state file: ${stateFile.getAbsolutePath()} $state")
        } else {

            def builder = new YamlBuilder()
            builder state: state
            stateFile.write(builder.toString(), "UTF-8")
        }
    }

    def getTodoistAccessToken() {
        def accessTokenFromEnv = System.getenv("TODOIST_ACCESS_TOKEN");
        if(StringUtils.isNotBlank(accessTokenFromEnv)) {
            return accessTokenFromEnv;
        } else if(StringUtils.isNotBlank(config.todoist.accessToken)) {
            return config.todoist.accessToken;
        } else {
            throw new IllegalStateException("Todoist access token must eithere be configured in config file or in env var TODOIST_ACCESS_TOKEN");
        }
    }

    def getLabelsToInclude() {

        def labelsToInclude = config.todoist.labelsToInclude
        
        if(!labelsToInclude instanceof List || ((List) labelsToInclude).size() == 0) {
            throw new IllegalStateException("todoist.labelsToInclude must be a non-empty array of label names")
        }
        return ((List) labelsToInclude);
    }

    def getProjectsToInclude() {
        def projectsToInclude = config.todoist.projectsToInclude
        
        if(!projectsToInclude || !projectsToInclude instanceof List || ((List) projectsToInclude).size() == 0) {
            return (List) []
        } else {
            return ((List) projectsToInclude);
        }
    }

    def syncLoop() {
        if(config.syncIntervalMs > 0) {
            while(true) {
                log.info("Syncing ...")
                try {
                    sync()
                    log.info("Sync run complete.")
                    configureCalDavHttpClients()
                } catch(Throwable t) {
                    log.error("Sync run failed with exception: ", t)
                }
                log.info("Sleeping for ${config.syncIntervalMs} ms before next sync run ...")
                Thread.sleep((long) config.syncIntervalMs)

            }
        } else {
            log.info("Syncing ...")
            sync()
            log.info("Sync run complete.")
        }
    }

    def sync() {
		
		log.info("Current Time Zone: " + currentTimeZoneOffset());
		
        def todoistAccessToken = getTodoistAccessToken();
        def todoistBasePath = new URL(todoistApiBaseUrl).getPath()
        def labelsToInclude = getLabelsToInclude();
        def projectsToInclude = getProjectsToInclude();

        log.info("Using todoistAccessToken: $todoistAccessToken")
        log.info("todoistApiBaseUrl: $todoistApiBaseUrl")
        log.info("todoistBasePath: $todoistBasePath")
        log.info("projectsToInclude: $projectsToInclude")
        log.info("labelsToInclude: $labelsToInclude")
        

        def restClient = new RESTClient(todoistApiBaseUrl)
		//ignoreSSLIssues(restClient)
		def syncToken = state.syncToken ?: '*'
        def todoistParamsForItems = [sync_token: syncToken, resource_types: '["items"]']
        def todoistParams = [sync_token: '*', resource_types: '["projects", "labels", "user"]']
        def todoistData = [:]
        def items = []
        def todoistUserId = 0
        def todoistEmail = ""
        try {
            log.info("Calling Todoist Sync API for items with params: $todoistParamsForItems")
            def itemsResponse = restClient.get(path: todoistBasePath + "/sync", query: todoistParamsForItems, headers: ["Authorization": "Bearer $todoistAccessToken"])
            if(itemsResponse.status != 200) {
                log.error("API call to Todoist to retrieve items failed with statusCode: ${itemsResponse.status} and body: ${itemsResponse.data.text}")
                throw new RuntimeException("API Call to Todoist failed.")
            }
            def itemsResponseData = itemsResponse.data
            syncToken = itemsResponseData.sync_token
            items = itemsResponseData.items
			log.info("Items before filtering: " + JsonOutput.prettyPrint(JsonOutput.toJson(items)))
            
            if(items.size() > 0) {
                log.info("Calling Todoist Sync API for metadata with params: $todoistParams")
                def metadataResponse = restClient.get(path: todoistBasePath + "/sync", query: todoistParams, headers: ["Authorization": "Bearer $todoistAccessToken"])
                if (metadataResponse.status == 200) {
                    log.info("Got 200 from Todoist Sync API")
                    todoistData = metadataResponse.data
                    todoistUserId = todoistData.user.id
                    todoistEmail = todoistData.user.email
                    def (labelsById, labelsByName) = collectMapsByIdAndName(todoistData.labels)
                    def (projectsById, projectsByName) = collectMapsByIdAndName(todoistData.projects)
                    log.info("labelsById: $labelsById")
                    log.info("labelsByName: $labelsByName")

                    def deletedItems = items.findAll { item -> item.is_deleted == true }

                    if(deletedItems.size() > 0) {
                        deletedItems.each { item -> 
                            def uid = generateUidFromItem(todoistUserId, item)
                            log.info("Deleting event from all calendars because it was deleted with uid: $uid")
                            deleteFromAllCalendars(uid)
                        }
                    }
    
                    items = resolveLabelNames(items)
					//log.info("Items with label names: " + JsonOutput.prettyPrint(JsonOutput.toJson(items)))
                    items = resolveProjectName(items, projectsById)
					//log.info("Items with project names: " + JsonOutput.prettyPrint(JsonOutput.toJson(items)))
                    items = removeItemsWithNoDueDates(items)
					//log.info("Items after filtering no due dates: " + JsonOutput.prettyPrint(JsonOutput.toJson(items)))
                    items = filterItemsForInclusionInCalendar(items, labelsToInclude, projectsToInclude)
					//log.info("Items after filtering for included labels: " + JsonOutput.prettyPrint(JsonOutput.toJson(items)))

                } else {
                    log.error("API call to Todoist to retrieve metadata failed with statusCode: ${metadataResponse.status} and body: ${metadataResponse.data.text}")
                    throw new RuntimeException("API Call to Todoist failed.")
                }
            }
			else {
				log.info("Todoist returned no items.")
			}
        } catch (Throwable e) {
            log.error("API Call to Todoist Failed: ", e)
            throw new RuntimeException("API Call to Todoist failed.", e)
        }


        def itemsJson = JsonOutput.prettyPrint(JsonOutput.toJson(items))

        log.info("Items: $itemsJson")

        def itemsByCalendar = sortItemsIntoCalendars(items)

        if(updateCalendars(todoistUserId, itemsByCalendar)) {
            log.info("Sync Succeeded, updating syncToken to: $syncToken")
            state.syncToken = syncToken
            saveStateFile(state)
        }

    }

    def sortItemsIntoCalendars(items) {
        def itemsByCalendar = [:]
        items.each { item ->
            def calendarName = identifyCalendarName(item)
            if(calendarName != null) {
                def list = itemsByCalendar[calendarName]
                if(list == null) {
                    list = []
                    itemsByCalendar[calendarName] = list
                }
                list.add(item)
            } else {
                log.warn("Couldn't find a calendar for item: ${item}")
            }
        }
        return itemsByCalendar
    }

    def identifyCalendarName(item) {
        def calendarName = null
        config.caldav.rules.each { rule ->
            log.info(rule)
            if(calendarName == null) {
                log.info("Applying rule: ${rule.rule}")
                if(rule.rule) {
                    def labelsMustMatch = rule.rule.split(" AND ")
                    def labelMatches = labelsMustMatch.collect { labelName ->
                        labelName = labelName.trim()
                        if(labelName.startsWith("NOT ")){
                            labelName = labelName.replaceFirst("NOT ", "")
                            if(labelName.startsWith("p:")) {
                                def projectName = labelName.replaceFirst("p:", "")
                                return item.project_name != projectName
                            } else {
                                return !item.label_names.contains(labelName)
                            }
                        } else {
                            if(labelName.startsWith("p:")) {
                                def projectName = labelName.replaceFirst("p:", "")
                                return item.project_name == projectName
                            } else {
                                return item.label_names.contains(labelName)
                            }
                        }
                    }
                    if(labelMatches.findAll{ it -> it == false}.size() == 0) {
                        log.info("Item: ${item.content} with labels: ${item.label_names} matched rule: ${rule.rule} for calendarName: ${rule.calendarName}")
                        calendarName = rule.calendarName
                    }
                }
            }

        }
        return calendarName
    }

    def doRateLimit() {
        if(config.rateLimitMs && config.rateLimitMs > 0) {
            log.debug("Sleeping for ${config.rateLimitMs} ms ...")
            Thread.sleep((long) config.rateLimitMs)
        }
    }

    def deleteFromCalendars(calendarNames, uid) {
        calendarNames.each { calendarName ->
            def calendarUrl = urlsByCalendar[calendarName]
            deleteIfExists(calendarName, calendarUrl, uid)
        }
    }

    def deleteFromAllCalendars(uid) {
        deleteFromCalendars(urlsByCalendar.keySet(), uid);
    }

    def retry(f, retries) {
        try {
            f()
        } catch(Throwable t) {
            if(retries > 0) {
                retries--
                doRateLimit()
                retry(f, retries)
            } else {
                throw t
            }
        }
    }

    def deleteIfExists(calendarName, calendarUrl, uid) {
        def eventUrl = calendarUrl + "/" + uid + ".ics"
        deleteWithRetry({
            try {
                // Fetch fresh httpClient inside closure to ensure we get latest after any reset
                def currentHttpClient = caldavHttpClientsByCalendar[calendarName]
                if (currentHttpClient == null) {
                    log.error("HttpClient not found for calendar: $calendarName after pool reset")
                    throw new RuntimeException("HttpClient not available for calendar: $calendarName")
                }
                CalDAVCollection collection = new CalDAVCollection(calendarUrl);
                log.debug("Deleting event: ${uid} from calendar: $calendarName with eventUrl: $eventUrl")
                collection.delete(currentHttpClient, eventUrl)
                log.debug("Successfully deleted event: $uid")
                    
            } catch(ResourceNotFoundException e) {
                // Ignore this since we are deleting it if it exists.
                log.debug("Couldn't delete event: $uid because it didn't exist in calendar: $calendarName")
            }
        }, calendarName, uid, 3)
    }

    def deleteWithRetry(f, calendarName, uid, retries) {
        int attempt = 0
        while(attempt <= retries) {
            try {
                attempt++
                // Proactively monitor and reset pool if needed
                checkAndResetPoolIfNeeded(calendarName)
                
                f()
                PoolStats ps = ((PoolingHttpClientConnectionManager) connectionManagers[calendarName]).getTotalStats()
                log.info("ConnectionPoolStats for calendarName: $calendarName $ps")
                return
            } catch(Throwable t) {
                PoolStats ps = ((PoolingHttpClientConnectionManager) connectionManagers[calendarName]).getTotalStats()
                log.warn("Couldn't delete event: $uid from calendar: $calendarName (attempt $attempt/$retries) - $ps because: ${t.message}")
                
                if(attempt <= retries) {
                    // Exponential backoff: 1s, 2s, 4s
                    def backoffMs = (1000L << (attempt - 1))
                    log.info("Waiting ${backoffMs}ms before retry...")
                    Thread.sleep(backoffMs)
                    doRateLimit()
                } else {
                    log.error("Failed to delete event: $uid after $retries retries", t)
                    // Don't throw - allow sync to continue
                }
            }
        }
    }

    def checkAndResetPoolIfNeeded(calendarName) {
        PoolingHttpClientConnectionManager cm = (PoolingHttpClientConnectionManager) connectionManagers[calendarName]
        if (cm == null) return
        
        PoolStats stats = cm.getTotalStats()
        int usagePercent = (stats.leased * 100) / stats.max
        
        if (usagePercent > poolThresholdPercent) {
            log.warn("Connection pool for $calendarName at ${usagePercent}% usage (${stats.leased}/${stats.max}). Resetting pool proactively...")
            resetPoolForCalendar(calendarName)
        }
    }

    def resetPoolForCalendar(calendarName) {
        try {
            // Prevent reset spam - only reset once per minute per calendar
            def now = System.currentTimeMillis()
            def lastReset = lastPoolResetTime[calendarName] ?: 0
            if (now - lastReset < 2000) {
                log.debug("Pool for $calendarName was reset recently, skipping...")
                return
            }
            
            log.info("Resetting HTTP connection pool for calendar: $calendarName")
            
            // Shutdown old client and manager
            PoolingHttpClientConnectionManager oldCm = connectionManagers[calendarName]
            CloseableHttpClient oldClient = caldavHttpClientsByCalendar[calendarName]
            
            if (oldClient != null) {
                try {
                    oldClient.close()
                    log.info("Closed old HttpClient for $calendarName")
                } catch (Exception e) {
                    log.warn("Error closing old HttpClient for $calendarName: ${e.message}")
                }
            }
            
            if (oldCm != null) {
                try {
                    oldCm.shutdown()
                    log.info("Shutdown old ConnectionManager for $calendarName")
                } catch (Exception e) {
                    log.warn("Error shutting down old ConnectionManager for $calendarName: ${e.message}")
                }
            }
            
            // Create fresh client and manager
            createHttpClientForCalendar(calendarName)
            lastPoolResetTime[calendarName] = now
            
            log.info("Successfully reset HTTP connection pool for calendar: $calendarName")
        } catch (Exception e) {
            log.error("Failed to reset connection pool for $calendarName: ${e.message}", e)
            // Don't throw - allow sync to continue with potentially degraded pool
        }
    }

    def createHttpClientForCalendar(calendarName) {
        def calConfig = config.caldav.calendars.find { it.name == calendarName }
        if (calConfig == null) {
            log.error("Calendar config not found for: $calendarName")
            return
        }
        
        def calUrl = calConfig.url
        def defaultAuthProps = config.caldav?.default?.auth
        def calAuthProps = calConfig.auth ?: defaultAuthProps
        
        def httpClientBuilder = HttpClients.custom()
        
        if(calAuthProps) {
            def scheme = AuthScheme.valueOf(calAuthProps.scheme)
            if(!SUPPORTED_AUTH_SCHEMES.contains(scheme)) {
                throw new IllegalArgumentException("Invalid scheme: $scheme. Valid Schemes: ${SUPPORTED_AUTH_SCHEMES}")
            }

            if(scheme == AuthScheme.BASIC) {
                def password = StringUtils.defaultString(calAuthProps.basicAuth?.password, System.getenv("CALDAV_AUTH_BASICAUTH_PASSWORD"))
                def username = calAuthProps.basicAuth?.username
                if(StringUtils.isBlank(password)) {
                    throw new IllegalArgumentException("Invalid password. It is blank")
                }
                if(StringUtils.isBlank(username)) {
                    throw new IllegalArgumentException("Invalid username. It is blank")
                }
                BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                def url = new URL(calUrl)
                credsProvider.setCredentials(
                        new AuthScope(url.getHost(), url.getPort()),
                        new UsernamePasswordCredentials(username, password))
                httpClientBuilder.setDefaultCredentialsProvider(credsProvider)
                log.info("Using username: $username with calendar name: $calendarName")
            }
            if(scheme == AuthScheme.GOOGLE_OAUTH2) {
                // Reuse singleton googleAuthProvider instead of creating new
                def username = calAuthProps.google?.username
                log.debug("Configuring Google OAuth2 Authentication Scheme for username: $username")
                if(StringUtils.isBlank(username)) {
                    throw new IllegalArgumentException("Invalid username. It is blank")
                }
                Credential credential = googleAuthProvider.getCredential(username)
                def googleOAuthInterceptor = new GoogleOAuthRequestInterceptor(credential)
                httpClientBuilder.addInterceptorFirst(googleOAuthInterceptor)
            }
        }
        
        def timeout = 5
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(timeout * 1000)
            .setConnectionRequestTimeout(timeout * 1000)
            .setSocketTimeout(timeout * 1000).build()
        
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager()
        int maxConnections = config.maxConnectionsPerHttpClient ?: 10
        cm.setMaxTotal(maxConnections)
        cm.setDefaultMaxPerRoute(maxConnections)
        
        connectionManagers[calendarName] = cm
        httpClientBuilder.setConnectionManager(cm)
        httpClientBuilder.setDefaultRequestConfig(requestConfig)
        
        def httpClient = httpClientBuilder.build()
        caldavHttpClientsByCalendar[calendarName] = httpClient
        
        log.info("Created fresh HttpClient for calendar: $calendarName")
    }

    def updateCalendars(todoistUserId, itemsByCalendar) {
        itemsByCalendar.each { calendarName, items ->
            def calendarUrl = urlsByCalendar[calendarName]
            def otherCalendarNames = urlsByCalendar.keySet().findAll { k -> !k.equals(calendarName)}
            
            log.info("Updating Events for Calendar: $calendarName: Count = ${items.size()} ...")
            log.info("Using Calendar Base URL: ${calendarUrl}")
            
            items.each { item ->
                def vevent = itemToEvent(todoistUserId, item, calendarName)
                Calendar calendar = new Calendar();
                calendar.getProperties().add(new ProdId(CalDAVConstants.PROC_ID_DEFAULT));
                calendar.getProperties().add(Version.VERSION_2_0);
                calendar.getProperties().add(CalScale.GREGORIAN);
                calendar.getComponents().add(vevent)
                def uid = vevent.getUid()?.getValue()

                if(dryRun) {
                    log.info("$DRY_RUN_PREFIX Would have deleted event: ${item.content} with uid: ${uid} from calendars: ${otherCalendarNames}")
                } else {
                    log.info("Deleting event: ${item.content} with uid: ${uid} from calendars: ${otherCalendarNames}")
                    deleteFromCalendars(otherCalendarNames, uid)
                }


                PoolStats ps = ((PoolingHttpClientConnectionManager) connectionManagers[calendarName]).getTotalStats()
                log.info("ConnectionPoolStats for calendarName: $calendarName $ps")

                if(dryRun) {
                    log.debug("$DRY_RUN_PREFIX Would have put event: ${item.content} with uid: ${uid} to calendar: $calendarName ...")
                } else {
                    deleteIfExists(calendarName, calendarUrl, uid)
                    log.info("Putting event: ${item.content} with uid: ${uid} to calendar: $calendarName ...")
                    retry({
                        // Fetch fresh httpClient inside closure to ensure we get latest after any reset
                        def httpClient = caldavHttpClientsByCalendar[calendarName]
                        if (httpClient == null) {
                            log.error("HttpClient not found for calendar: $calendarName after pool reset")
                            throw new RuntimeException("HttpClient not available for calendar: $calendarName")
                        }
                        CalDAVCollection collection = new CalDAVCollection(calendarUrl);
                        collection.add(httpClient, calendar, false)
                    }, 3)
                    log.info("Putting event: ${item.content} with uid: ${uid} to calendar: $calendarName successful.")
                    doRateLimit()
                }
            }
        }
        return true
    }

    def dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
    def dateFormat = new SimpleDateFormat("yyyy-MM-ddZ")

    def base32Codec = new Base32(true)

    def todoistToICalPriority(todoistPriority) {
        if(todoistPriority == 4) {
            return 1
        } else if(todoistPriority == 3) {
            return 2
        } else if(todoistPriority == 2) {
            return 3
        } else if(todoistPriority == 1) {
            return 4
        } else {
            return 5
        }
    }

    def generateUidFromItem(todoistUserId, item) {
        def id = "${todoistUserId}-${item.id}"
        def encodedId = base32Codec.encodeToString(id.getBytes("UTF-8")).replace("=", "").toLowerCase()
        log.debug("Calendar Event ID: $id")
        log.debug("Calendar Event Encoded ID: $encodedId")
        return encodedId
    }

    def itemToEvent(todoistUserId, item, calendarName) {
        VEvent event = new VEvent();
        def summary = item.content
        if(prefixesByCalendar[calendarName]) {
            summary = prefixesByCalendar[calendarName] + summary
        }
        event.getProperties().add(new Summary(summary))
        event.getProperties().add(new Description(renderDescription(item)))
        event.getProperties().add(new Uid(generateUidFromItem(todoistUserId, item)))
        def iCalPriority = todoistToICalPriority(item.priority)
        event.getProperties().add(new Priority(iCalPriority))
        /* TODO: Add Configurable Color
        def color = new Color()
        color.setValue("silver")
        event.getProperties().add(color)
        */
		log.debug("Found Due Date: ${item.due.date} ")
        def dueDateWithTimeZone = item.due.date
		if(!dueDateWithTimeZone.endsWith("Z")) {
			def timeZoneAtDate = getTimeZoneOffsetAtDateTime(dueDateWithTimeZone)
            log.debug("Appended Timezone: ${timeZoneAtDate}");
            dueDateWithTimeZone += timeZoneAtDate
		}
        dueDateWithTimeZone = dueDateWithTimeZone.replace("Z", "+0000")
		
        def startDate = null
        try {
			startDate = dateTimeFormat.parse(dueDateWithTimeZone)
		} catch(ParseException e) {
			try {
				startDate = dateFormat.parse(dueDateWithTimeZone)
				item.all_day = true
			} catch(ParseException e2) {
			  throw e
			}
			
		}
        log.debug("StartDate parsed: $startDate")
        
        // TODO: Customize the "start time" for all-day events
        if(item.all_day) {
            startDate.setHours(9)
            startDate.setMinutes(0)
            startDate.setSeconds(0)
        }

        def startDateTimeAsStr = dateTimeFormat.format(startDate)
        log.debug("startDateTimeAsStr: $startDateTimeAsStr")

        event.getProperties().add(new DtStart(new DateTime(startDate.getTime())));

        def tLabel = item.label_names.find { l -> l.startsWith("t") && l.length() < 3 }

        def defaultDurationMinutes = 30
        
        // Dur is days, hours, minutes, seconds
        Dur dur = new Dur(0, 0, 30, 0)

        if(tLabel != null) {
            // TODO: Make tLabels configurable?
            def tLabelRemainder = Integer.parseInt(tLabel.substring(1))
            if(tLabelRemainder > 10) {
                dur = new Dur(0, 0, tLabelRemainder, 0)
            }
            else if(tLabelRemainder == 0){
                dur = new Dur(0, 0, defaultDurationMinutes, 0)
            }
            else {
                dur = new Dur(0, tLabelRemainder, 0, 0)
            }
        }
        else {
            //TODO: Make default duration configurable
            dur = new Dur(0, 0, defaultDurationMinutes, 0)
        }

        // Parse the "duration" from Todoist.
        if(item.duration) {
            def amount = item.duration.amount
            def unit = item.duration.unit
            if(unit == "minute") {
                dur = new Dur(0, 0, amount, 0)
            } else if(unit == "hour") {
                dur = new Dur(0, amount, 0, 0)
            }
            log.debug("Parsed duration from Todoist into: $dur")
        }

        event.getProperties().add(new Duration(dur))

        log.debug("Built event:\n${event}")

        return event;

    }

    def collectMapsByIdAndName(arr) {
        def byId = [:]
        def byName = [:]
        arr.each { it ->
            byId[it.id] = it.name
            byName[it.name] = it.id
        }
        return [byId, byName]
    }

    def filterItemsForInclusionInCalendar(items, labelsToInclude, projectsToInclude) {
        return items.findAll { item ->
            def includedLabels = item.labels.findAll { labelName ->
                labelsToInclude.contains(labelName)
            }
            def includedByLabels = includedLabels.size() > 0
            def includedByProjectName = projectsToInclude.contains(item.project_name)
            return includedByLabels || includedByProjectName
        }

    }

    def resolveLabelNames(items) {
        items.each { item ->
            item.label_names = item.labels
        }
        return items
    }

    def resolveProjectName(items, projectsById) {
        items.each { item ->
            item.project_name = projectsById[item.project_id]
        }
        return items
    }

    def removeItemsWithNoDueDates(items) {
        return items.findAll { item -> item.due && item.due.date != null }
    }

    def renderDescription(item) {
        """Project: ${item.project_name}
        |Labels: ${item.label_names.join(", ")}
        |Priority: p${5-item.priority}
        """.stripMargin()
    }








}
