package de.kosit.validationtool.daemon;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import de.kosit.validationtool.api.Configuration;
import de.kosit.validationtool.impl.ConversionService;
import de.kosit.validationtool.impl.DefaultCheck;
import de.kosit.validationtool.model.daemon.HealthType;

/**
 * HTTP-Daemon für die Bereitstellung der Prüf-Funktionalität via http.
 *
 * @author Roula Antoun
 */
@RequiredArgsConstructor
@Setter
@Slf4j
public class Daemon {

    private static final String DEFAULT_HOST = "localhost";

    private static final int DEFAULT_PORT = 8080;

    private final String hostName;

    private final int port;

    private final int threadCount;

    @Setter(AccessLevel.PRIVATE)
    private boolean guiDisabled = false;

    public void disableGui() {
        guiDisabled = true;
    }

    /**
     * Methode zum Starten des Servers
     * 
     * @param config the configuration to use
     */
    public void startServer(final Configuration config) {
        HttpServer server = null;
        try {
            final ConversionService healthConverter = new ConversionService();
            healthConverter.initialize(HealthType.class.getPackage());
            final ConversionService converter = new ConversionService();

            server = HttpServer.create(getSocket(), 0);
            server.createContext("/", createRootHandler(config));
            server.createContext("/server/health", new HealthHandler(config, healthConverter));
            server.createContext("/server/config", new ConfigHandler(config, converter));
            server.setExecutor(createExecutor());
            server.start();
            log.info("Server {} started", server.getAddress());
        } catch (final IOException e) {
            log.error("Error starting HttpServer for Valdidator: {}", e.getMessage(), e);
        }
    }

    private HttpHandler createRootHandler(Configuration config) {
        HttpHandler rootHandler;
        final DefaultCheck check = new DefaultCheck(config);
        final CheckHandler checkHandler = new CheckHandler(check, config.getContentRepository().getProcessor());
        if (!guiDisabled) {
            GuiHandler gui = new GuiHandler();
            rootHandler = new RoutingHandler(checkHandler, gui);
        } else {
            rootHandler = checkHandler;
        }
        return rootHandler;
    }

    private ExecutorService createExecutor() {
        return Executors.newFixedThreadPool(this.threadCount > 0 ? this.threadCount : Runtime.getRuntime().availableProcessors());
    }

    private InetSocketAddress getSocket() {
        return new InetSocketAddress(defaultIfBlank(this.hostName, DEFAULT_HOST), this.port > 0 ? this.port : DEFAULT_PORT);
    }
}
