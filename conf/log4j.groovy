log4j {
    appender.stdout = "org.apache.log4j.ConsoleAppender"
    appender."stdout.layout"="org.apache.log4j.PatternLayout"
    appender."stdout.layout.ConversionPattern"="%d %5p %c{1}:%L - %m%n"
    appender.file = "org.apache.log4j.RollingFileAppender"
    appender."file.layout"="org.apache.log4j.PatternLayout"
    appender."file.layout.ConversionPattern"="%d %5p %c{1}:%L - %m%n"
    appender."file.file"="logs/todoist-ical-sync.log"
    appender."file.MaxBackupIndex"="2"
    appender."file.MaxFileSize"="100MB"
    appender."file.Append"="true"
    rootLogger="debug,file,stdout"
    logger.org.apache.xerces.parsers="error,file,stdout"
}