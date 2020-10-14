/*
 * Copyright 2017-2020  Koordinierungsstelle für IT-Standards (KoSIT)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.kosit.validationtool.cmd;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.fusesource.jansi.AnsiRenderer.Code;

import lombok.extern.slf4j.Slf4j;

import de.kosit.validationtool.api.Configuration;
import de.kosit.validationtool.api.Input;
import de.kosit.validationtool.api.InputFactory;
import de.kosit.validationtool.api.Result;
import de.kosit.validationtool.cmd.assertions.Assertions;
import de.kosit.validationtool.cmd.report.Line;
import de.kosit.validationtool.config.ConfigurationLoader;
import de.kosit.validationtool.daemon.Daemon;
import de.kosit.validationtool.impl.ConversionService;
import de.kosit.validationtool.impl.EngineInformation;
import de.kosit.validationtool.impl.Printer;
import de.kosit.validationtool.impl.xml.ProcessorProvider;

import net.sf.saxon.s9api.Processor;

/**
 * Actual evaluation and processing of CommandLineOptions argumtens.
 * 
 * @author Andreas Penski
 */
@Slf4j
@SuppressWarnings("squid:S3725")
public class Validator {

    private Validator() {
        // hide
    }

    /**
     * Hauptprogramm für die Kommandozeilen-Applikation.
     *
     * @param cmd parsed commandline.
     */
    static ReturnValue mainProgram(final CommandLineOptions cmd) {
        greeting();
        final ReturnValue returnValue;
        try {
            if (cmd.getDaemonOptions().isDaemonMode()) {
                startDaemonMode(cmd);
                returnValue = ReturnValue.DAEMON_MODE;
            } else {
                returnValue = processActions(cmd);
            }
        } catch (final Exception e) {
            Printer.writeErr(e.getMessage());
            if (cmd.isDebugOutput()) {
                log.error(e.getMessage(), e);
            } else {
                log.error(e.getMessage());
            }
            return ReturnValue.CONFIGURATION_ERROR;
        }
        return returnValue;
    }

    private static void greeting() {
        Printer.writeOut("{0} version {1}", EngineInformation.getName(), EngineInformation.getVersion());
    }

    private static int determineThreads(final CommandLineOptions.DaemonOptions cmd) {
        int threads = Runtime.getRuntime().availableProcessors();
        if (cmd.getWorkerCount() > 0) {
            threads = cmd.getWorkerCount();
        }
        return threads;
    }

    private static void startDaemonMode(final CommandLineOptions cmd) {
        // final Option[] unavailable = new Option[] { PRINT, CHECK_ASSERTIONS, DEBUG, OUTPUT, EXTRACT_HTML,
        // REPORT_POSTFIX, REPORT_PREFIX };
        // warnUnusedOptions(cmd, unavailable, true);
        final List<Configuration> configuration = getConfiguration(cmd).stream().map(config -> {

            final Configuration c = config.build(ProcessorProvider.getProcessor());
            return c;
        }).collect(Collectors.toList());
        printScenarios(configuration);
        final CommandLineOptions.DaemonOptions daemonOptions = cmd.getDaemonOptions();
        final Daemon validDaemon = new Daemon(daemonOptions.getHost(), daemonOptions.getPort(), determineThreads(daemonOptions));
        validDaemon.setGuiEnabled(!daemonOptions.isDisableGUI());
        Printer.writeOut("\nStarting daemon mode ...");
        validDaemon.startServer(ProcessorProvider.getProcessor(), configuration.toArray(new Configuration[configuration.size()]));
    }

    private static void warnUnusedOptions(final CommandLineOptions cmd, final String[] unavailable, final boolean daemon) {
        // Arrays.stream(cmd.getOptions()).filter(o -> ArrayUtils.contains(unavailable, o))
        // .map(o -> "The option " + o.getLongOpt() + " is not available in daemon mode").forEach(log::error);
        // if (daemon && !cmd.getArgList().isEmpty()) {
        // log.info("Ignoring test targets in daemon mode");
        // }
    }

    private static ReturnValue processActions(final CommandLineOptions cmd) throws IOException {
        long start = System.currentTimeMillis();
        // final Option[] unavailable = new Option[] { HOST, PORT, WORKER_COUNT, DISABLE_GUI };
        // warnUnusedOptions(cmd, unavailable, false);
        final Processor processor = ProcessorProvider.getProcessor();
        final List<Configuration> config = getConfiguration(cmd).stream().map(b -> {
            return b.build(processor);
        }).collect(Collectors.toList());
        printScenarios(config);
        final InternalCheck check = new InternalCheck(processor, config.toArray(new Configuration[config.size()]));
        final Path outputDirectory = determineOutputDirectory(cmd.getCliOptions());
        final CommandLineOptions.CliOptions cliOptions = cmd.getCliOptions();
        if (cliOptions.isExtractHtml()) {
            check.getCheckSteps().add(new ExtractHtmlContentAction(processor, outputDirectory));
        }
        check.getCheckSteps().add(new SerializeReportAction(outputDirectory, processor, determineNamingStrategy(cmd.getCliOptions())));
        if (cliOptions.isSerializeInput()) {
            check.getCheckSteps().add(new SerializeReportInputAction(outputDirectory, check.getConversionService()));
        }
        if (cliOptions.isPrintReport()) {
            check.getCheckSteps().add(new PrintReportAction(processor));
        }

        if (cliOptions.getAssertions() != null) {
            final Assertions assertions = loadAssertions(cliOptions.getAssertions());
            check.getCheckSteps().add(new CheckAssertionAction(assertions, processor));
        }
        if (cliOptions.isPrintMemoryStats()) {
            check.getCheckSteps().add(new PrintMemoryStats());
        }
        log.info("Setup completed in {}ms\n", System.currentTimeMillis() - start);

        final Collection<Input> targets = determineTestTargets(cmd.getCliOptions());
        start = System.currentTimeMillis();
        final Map<String, Result> results = new HashMap<>();
        Printer.writeOut("\nProcessing of {0} objects started", targets.size());
        long tick = System.currentTimeMillis();
        for (final Input input : targets) {
            results.put(input.getName(), check.checkInput(input));
            if (((System.currentTimeMillis() - tick) / 1000) > 5) {
                tick = System.currentTimeMillis();
                Printer.writeOut("{0}/{1} objects processed", results.size(), targets.size());
            }
        }
        final long processingTime = System.currentTimeMillis() - start;
        Printer.writeOut("Processing of {0} objects completed in {1}ms", targets.size(), processingTime);

        check.printResults(results);
        log.info("Processing {} object(s) completed in {}ms", targets.size(), processingTime);
        return check.isSuccessful(results) ? ReturnValue.SUCCESS : ReturnValue.createFailed(check.getNotAcceptableCount(results));
    }

    private static List<ConfigurationLoader> getConfiguration(final CommandLineOptions cmd) {
        return cmd.getScenarios().stream().map(s -> {
            final URI scenarioLocation = determineDefinition(s);
            final URI repositoryLocation = determineRepository(cmd);
            reportConfiguration(scenarioLocation, repositoryLocation);
            return Configuration.load(scenarioLocation, repositoryLocation);
        }).collect(Collectors.toList());

    }

    private static void reportConfiguration(final URI scenarioLocation, final URI repositoryLocation) {
        Printer.writeOut("Loading scenarios from  {0}", scenarioLocation);
        Printer.writeOut("Using repository  {0}", repositoryLocation);
    }

    private static void printScenarios(final List<Configuration> configurations) {
        configurations.forEach(configuration -> {
            Printer.writeOut("Loaded \"{0}\" by {1} from {2} ", configuration.getName(), configuration.getAuthor(),
                    configuration.getDate());
            Printer.writeOut("\nThe following scenarios are available:");
            configuration.getScenarios().forEach(e -> {
                final Line line = new Line(Code.GREEN);
                line.add("  * " + e.getName());
                Printer.writeOut(line.render(false, false));
            });
        });
    }

    private static NamingStrategy determineNamingStrategy(final CommandLineOptions.CliOptions cmd) {
        final DefaultNamingStrategy namingStrategy = new DefaultNamingStrategy();
        if (isNotEmpty(cmd.getReportPrefix())) {
            namingStrategy.setPrefix(cmd.getReportPrefix());
        }
        if (isNotEmpty(cmd.getReportPostfix())) {
            namingStrategy.setPostfix(cmd.getReportPostfix());
        }
        return namingStrategy;
    }

    private static Assertions loadAssertions(final Path p) {
        Assertions a = null;
        if (Files.exists(p)) {
            final ConversionService c = new ConversionService();
            c.initialize(de.kosit.validationtool.cmd.assertions.ObjectFactory.class.getPackage());
            a = c.readXml(p.toUri(), Assertions.class);
        }
        return a;
    }

    private static Path determineOutputDirectory(final CommandLineOptions.CliOptions cmd) {
        final Path dir;
        if (cmd.getOutputPath() != null) {
            dir = cmd.getOutputPath();
            if ((!Files.exists(dir) && !dir.toFile().mkdirs()) || !Files.isDirectory(dir)) {
                throw new IllegalStateException(String.format("Invalid target directory %s specified", dir.toString()));
            }
        } else {
            dir = Paths.get(""/* cwd */);
        }
        return dir;
    }

    private static Collection<Input> determineTestTargets(final CommandLineOptions.CliOptions cmd) throws IOException {
        final Collection<Input> targets = new ArrayList<>();
        if (cmd.getFiles() != null && !cmd.getFiles().isEmpty()) {
            cmd.getFiles().forEach(e -> targets.addAll(determineTestTarget(e)));
        }
        if (isPiped()) {
            targets.add(readFromPipe());
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("No test targets found. Nothing to check. Will quit now!");
        }
        return targets;
    }

    private static boolean isPiped() throws IOException {
        return System.in.available() > 0;
    }

    private static Input readFromPipe() {
        return InputFactory.read(System.in, "stdin");
    }

    private static Collection<Input> determineTestTarget(final Path d) {
        if (Files.isDirectory(d)) {
            return listDirectoryTargets(d);
        } else if (Files.exists(d)) {
            return Collections.singleton(InputFactory.read(d));
        }
        log.warn("The specified test target {} does not exist. Will be ignored", d);
        return Collections.emptyList();

    }

    private static Collection<Input> listDirectoryTargets(final Path d) {
        try ( final Stream<Path> stream = Files.list(d) ) {
            return stream.filter(path -> path.toString().toLowerCase().endsWith(".xml")).map(InputFactory::read)
                    .collect(Collectors.toList());
        } catch (final IOException e) {
            throw new IllegalStateException("IOException while list directory content. Can not determine test targets.", e);
        }

    }

    private static URI determineRepository(final CommandLineOptions cmd) {
        if (cmd.getRepositories() != null) {
            final Path d = cmd.getRepositories();
            if (Files.isDirectory(d)) {
                return d.toUri();
            } else {
                throw new IllegalArgumentException(
                        String.format("Not a valid path for repository definition specified: '%s'", d.toAbsolutePath()));
            }
        }
        return null;
    }

    private static URI determineDefinition(final Path f) {
        if (Files.isRegularFile(f)) {
            return f.toAbsolutePath().toUri();
        } else {
            throw new IllegalArgumentException(
                    String.format("Not a valid path for scenario definition specified: '%s'", f.toAbsolutePath()));
        }
    }

}
