package com.blissfuljuan.dieapi.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Captures everything the application logs while open (message, MDC values, stack traces) and raises the level of the
 * given loggers to DEBUG so that verbose logging cannot hide a leak. Close it to restore the previous levels.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Map<Logger, Level> previousLevels = new HashMap<>();

    public LogCapture(String... debugLoggers) {
        for (String name : debugLoggers) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            previousLevels.put(logger, logger.getLevel());
            logger.setLevel(Level.DEBUG);
        }
        appender.start();
        root.addAppender(appender);
    }

    /** One string per event: formatted message, MDC, and the full stack trace if any. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent e : new ArrayList<>(appender.list)) {
            StringBuilder sb = new StringBuilder(e.getLoggerName()).append(' ').append(e.getFormattedMessage())
                .append(' ').append(e.getMDCPropertyMap());
            IThrowableProxy t = e.getThrowableProxy();
            if (t != null) sb.append('\n').append(ThrowableProxyUtil.asString(t));
            lines.add(sb.toString());
        }
        return lines;
    }

    public String text() {
        return lines().stream().collect(Collectors.joining("\n"));
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        previousLevels.forEach(Logger::setLevel);
    }
}
