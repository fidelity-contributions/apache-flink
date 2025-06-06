/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.client.cli;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.InvalidProgramException;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.dag.Pipeline;
import org.apache.flink.client.ClientUtils;
import org.apache.flink.client.FlinkPipelineTranslationUtil;
import org.apache.flink.client.deployment.ClusterClientFactory;
import org.apache.flink.client.deployment.ClusterClientServiceLoader;
import org.apache.flink.client.deployment.ClusterDescriptor;
import org.apache.flink.client.deployment.DefaultClusterClientServiceLoader;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.client.deployment.application.cli.ApplicationClusterDeployer;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.PackagedProgram;
import org.apache.flink.client.program.PackagedProgramUtils;
import org.apache.flink.client.program.ProgramInvocationException;
import org.apache.flink.client.program.ProgramMissingJobException;
import org.apache.flink.client.program.ProgramParametrizationException;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.core.execution.CheckpointType;
import org.apache.flink.core.execution.DefaultExecutorServiceLoader;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.plugin.PluginUtils;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.security.SecurityConfiguration;
import org.apache.flink.runtime.security.SecurityUtils;
import org.apache.flink.runtime.util.EnvironmentInformation;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.min;
import static org.apache.flink.client.cli.CliFrontendParser.HELP_OPTION;
import static org.apache.flink.util.Preconditions.checkNotNull;

/** Implementation of a simple command line frontend for executing programs. */
public class CliFrontend {

    private static final Logger LOG = LoggerFactory.getLogger(CliFrontend.class);

    private static final int INITIAL_RET_CODE = 31;

    // actions
    private static final String ACTION_RUN = "run";
    private static final String ACTION_INFO = "info";
    private static final String ACTION_LIST = "list";
    private static final String ACTION_CANCEL = "cancel";
    private static final String ACTION_STOP = "stop";
    private static final String ACTION_SAVEPOINT = "savepoint";
    private static final String ACTION_CHECKPOINT = "checkpoint";

    // configuration dir parameters
    private static final String CONFIG_DIRECTORY_FALLBACK_1 = "../conf";
    private static final String CONFIG_DIRECTORY_FALLBACK_2 = "conf";

    // --------------------------------------------------------------------------------------------

    private final Configuration configuration;

    private final List<CustomCommandLine> customCommandLines;

    private final Options customCommandLineOptions;

    private final ClusterClientServiceLoader clusterClientServiceLoader;

    public CliFrontend(Configuration configuration, List<CustomCommandLine> customCommandLines) {
        this(configuration, new DefaultClusterClientServiceLoader(), customCommandLines);
    }

    public CliFrontend(
            Configuration configuration,
            ClusterClientServiceLoader clusterClientServiceLoader,
            List<CustomCommandLine> customCommandLines) {
        this.configuration = checkNotNull(configuration);
        this.customCommandLines = checkNotNull(customCommandLines);
        this.clusterClientServiceLoader = checkNotNull(clusterClientServiceLoader);

        FileSystem.initialize(
                configuration, PluginUtils.createPluginManagerFromRootFolder(configuration));

        this.customCommandLineOptions = new Options();

        for (CustomCommandLine customCommandLine : customCommandLines) {
            customCommandLine.addGeneralOptions(customCommandLineOptions);
            customCommandLine.addRunOptions(customCommandLineOptions);
        }
    }

    // --------------------------------------------------------------------------------------------
    //  Getter & Setter
    // --------------------------------------------------------------------------------------------

    /**
     * Getter which returns a copy of the associated configuration.
     *
     * @return Copy of the associated configuration
     */
    public Configuration getConfiguration() {
        Configuration copiedConfiguration = new Configuration();

        copiedConfiguration.addAll(configuration);

        return copiedConfiguration;
    }

    public Options getCustomCommandLineOptions() {
        return customCommandLineOptions;
    }

    // --------------------------------------------------------------------------------------------
    //  Execute Actions
    // --------------------------------------------------------------------------------------------

    /**
     * Executions the run action.
     *
     * @param args Command line arguments for the run action.
     */
    protected void run(String[] args) throws Exception {
        LOG.info("Running 'run' command.");

        final Options commandOptions = CliFrontendParser.getRunCommandOptions();
        final CommandLine commandLine = getCommandLine(commandOptions, args, true);

        // evaluate help flag
        if (commandLine.hasOption(HELP_OPTION.getOpt())) {
            CliFrontendParser.printHelpForRun(customCommandLines);
            return;
        }

        final CustomCommandLine activeCommandLine =
                validateAndGetActiveCommandLine(checkNotNull(commandLine));

        final Configuration effectiveConfiguration =
                getEffectiveConfiguration(activeCommandLine, commandLine);

        if (isDeploymentTargetApplication(effectiveConfiguration)) {
            final ApplicationDeployer deployer =
                    new ApplicationClusterDeployer(clusterClientServiceLoader);

            final ProgramOptions programOptions;

            // No need to set a jarFile path for PyFlink job.
            if (ProgramOptionsUtils.isPythonEntryPoint(commandLine)) {
                programOptions = ProgramOptionsUtils.createPythonProgramOptions(commandLine);
                updateEffectiveConfiguration(
                        effectiveConfiguration, programOptions, Collections.emptyList());
            } else {
                programOptions = new ProgramOptions(commandLine);
                programOptions.validate();
                final URI uri = PackagedProgramUtils.resolveURI(programOptions.getJarFilePath());
                updateEffectiveConfiguration(
                        effectiveConfiguration,
                        programOptions,
                        Collections.singletonList(uri.toString()));
            }

            final ApplicationConfiguration applicationConfiguration =
                    new ApplicationConfiguration(
                            programOptions.getProgramArgs(),
                            programOptions.getEntryPointClassName());
            deployer.run(effectiveConfiguration, applicationConfiguration);
        } else {
            final ProgramOptions programOptions = ProgramOptions.create(commandLine);

            final List<URL> jobJars = getJobJarAndDependencies(programOptions);

            updateEffectiveConfiguration(effectiveConfiguration, programOptions, jobJars);

            LOG.debug("Effective executor configuration: {}", effectiveConfiguration);

            try (PackagedProgram program =
                    getPackagedProgram(programOptions, effectiveConfiguration)) {
                executeProgram(effectiveConfiguration, program);
            }
        }
    }

    protected boolean isDeploymentTargetApplication(final Configuration effectiveConfiguration) {
        final String executionTarget =
                effectiveConfiguration
                        .getOptional(DeploymentOptions.TARGET)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                String.format(
                                                        "Config '%s' has to be set.",
                                                        DeploymentOptions.TARGET.key())));

        return executionTarget.trim().endsWith("application");
    }

    /** Get all provided libraries needed to run the program from the ProgramOptions. */
    private List<URL> getJobJarAndDependencies(ProgramOptions programOptions)
            throws CliArgsException {
        String entryPointClass = programOptions.getEntryPointClassName();
        String jarFilePath = programOptions.getJarFilePath();

        try {
            File jarFile = jarFilePath != null ? getJarFile(jarFilePath) : null;
            return PackagedProgram.getJobJarAndDependencies(jarFile, entryPointClass);
        } catch (FileNotFoundException | ProgramInvocationException e) {
            throw new CliArgsException(
                    "Could not get job jar and dependencies from JAR file: " + e.getMessage(), e);
        }
    }

    private PackagedProgram getPackagedProgram(
            ProgramOptions programOptions, Configuration effectiveConfiguration)
            throws ProgramInvocationException, CliArgsException {
        PackagedProgram program;
        try {
            LOG.info("Building program from JAR file");
            program = buildProgram(programOptions, effectiveConfiguration);
        } catch (FileNotFoundException e) {
            throw new CliArgsException(
                    "Could not build the program from JAR file: " + e.getMessage(), e);
        }
        return program;
    }

    private Configuration getEffectiveConfiguration(
            final CustomCommandLine activeCustomCommandLine, final CommandLine commandLine)
            throws FlinkException {

        final Configuration effectiveConfiguration = new Configuration(configuration);

        final Configuration commandLineConfiguration =
                checkNotNull(activeCustomCommandLine).toConfiguration(commandLine);

        effectiveConfiguration.addAll(commandLineConfiguration);

        return effectiveConfiguration;
    }

    private <T> void updateEffectiveConfiguration(
            final Configuration effectiveConfiguration,
            final ProgramOptions programOptions,
            final List<T> jobJars) {

        final ExecutionConfigAccessor executionParameters =
                ExecutionConfigAccessor.fromProgramOptions(programOptions, jobJars);

        executionParameters.applyToConfiguration(effectiveConfiguration);

        LOG.debug(
                "Effective configuration after Flink conf, custom commandline, and program options: {}",
                effectiveConfiguration);
    }

    /**
     * Executes the info action.
     *
     * @param args Command line arguments for the info action.
     */
    protected void info(String[] args) throws Exception {
        LOG.info("Running 'info' command.");

        final Options commandOptions = CliFrontendParser.getInfoCommandOptions();

        final CommandLine commandLine = getCommandLine(commandOptions, args, true);

        final ProgramOptions programOptions = ProgramOptions.create(commandLine);

        // evaluate help flag
        if (commandLine.hasOption(HELP_OPTION.getOpt())) {
            CliFrontendParser.printHelpForInfo();
            return;
        }

        // -------- build the packaged program -------------

        LOG.info("Building program from JAR file");

        PackagedProgram program = null;

        try {
            LOG.info("Creating program plan dump");

            final CustomCommandLine activeCommandLine =
                    validateAndGetActiveCommandLine(checkNotNull(commandLine));

            final Configuration effectiveConfiguration =
                    getEffectiveConfiguration(activeCommandLine, commandLine);

            updateEffectiveConfiguration(
                    effectiveConfiguration,
                    programOptions,
                    getJobJarAndDependencies(programOptions));

            program = buildProgram(programOptions, effectiveConfiguration);

            int parallelism = programOptions.getParallelism();
            if (ExecutionConfig.PARALLELISM_DEFAULT == parallelism) {
                parallelism = getDefaultParallelism(effectiveConfiguration);
            }

            Pipeline pipeline =
                    PackagedProgramUtils.getPipelineFromProgram(
                            program, effectiveConfiguration, parallelism, true);
            String jsonPlan =
                    FlinkPipelineTranslationUtil.translateToJSONExecutionPlan(
                            program.getUserCodeClassLoader(), pipeline);

            if (jsonPlan != null) {
                System.out.println(
                        "----------------------- Execution Plan -----------------------");
                System.out.println(jsonPlan);
                System.out.println(
                        "--------------------------------------------------------------");
            } else {
                System.out.println("JSON plan could not be generated.");
            }

            String description = program.getDescription();
            System.out.println();
            if (description != null) {
                System.out.println(description);
            } else {
                System.out.println("No description provided.");
            }
        } finally {
            if (program != null) {
                program.close();
            }
        }
    }

    /**
     * Executes the list action.
     *
     * @param args Command line arguments for the list action.
     */
    protected void list(String[] args) throws Exception {
        LOG.info("Running 'list' command.");

        final Options commandOptions = CliFrontendParser.getListCommandOptions();
        final CommandLine commandLine = getCommandLine(commandOptions, args, false);

        ListOptions listOptions = new ListOptions(commandLine);

        // evaluate help flag
        if (listOptions.isPrintHelp()) {
            CliFrontendParser.printHelpForList(customCommandLines);
            return;
        }

        final boolean showRunning;
        final boolean showScheduled;
        final boolean showAll;

        // print running and scheduled jobs if not option supplied
        if (!listOptions.showRunning() && !listOptions.showScheduled() && !listOptions.showAll()) {
            showRunning = true;
            showScheduled = true;
            showAll = false;
        } else {
            showRunning = listOptions.showRunning();
            showScheduled = listOptions.showScheduled();
            showAll = listOptions.showAll();
        }

        final CustomCommandLine activeCommandLine = validateAndGetActiveCommandLine(commandLine);

        runClusterAction(
                activeCommandLine,
                commandLine,
                (clusterClient, effectiveConfiguration) ->
                        listJobs(clusterClient, showRunning, showScheduled, showAll));
    }

    private <ClusterID> void listJobs(
            ClusterClient<ClusterID> clusterClient,
            boolean showRunning,
            boolean showScheduled,
            boolean showAll)
            throws FlinkException {
        Collection<JobStatusMessage> jobDetails;
        try {
            CompletableFuture<Collection<JobStatusMessage>> jobDetailsFuture =
                    clusterClient.listJobs();

            logAndSysout("Waiting for response...");
            jobDetails = jobDetailsFuture.get();

        } catch (Exception e) {
            Throwable cause = ExceptionUtils.stripExecutionException(e);
            throw new FlinkException("Failed to retrieve job list.", cause);
        }

        LOG.info("Successfully retrieved list of jobs");

        final List<JobStatusMessage> runningJobs = new ArrayList<>();
        final List<JobStatusMessage> scheduledJobs = new ArrayList<>();
        final List<JobStatusMessage> terminatedJobs = new ArrayList<>();
        jobDetails.forEach(
                details -> {
                    if (details.getJobState() == JobStatus.CREATED
                            || details.getJobState() == JobStatus.INITIALIZING) {
                        scheduledJobs.add(details);
                    } else if (!details.getJobState().isGloballyTerminalState()) {
                        runningJobs.add(details);
                    } else {
                        terminatedJobs.add(details);
                    }
                });

        if (showRunning || showAll) {
            if (runningJobs.isEmpty()) {
                System.out.println("No running jobs.");
            } else {
                System.out.println(
                        "------------------ Running/Restarting Jobs -------------------");
                printJobStatusMessages(runningJobs);
                System.out.println(
                        "--------------------------------------------------------------");
            }
        }
        if (showScheduled || showAll) {
            if (scheduledJobs.isEmpty()) {
                System.out.println("No scheduled jobs.");
            } else {
                System.out.println(
                        "----------------------- Scheduled Jobs -----------------------");
                printJobStatusMessages(scheduledJobs);
                System.out.println(
                        "--------------------------------------------------------------");
            }
        }
        if (showAll) {
            if (!terminatedJobs.isEmpty()) {
                System.out.println(
                        "---------------------- Terminated Jobs -----------------------");
                printJobStatusMessages(terminatedJobs);
                System.out.println(
                        "--------------------------------------------------------------");
            }
        }
    }

    private static void printJobStatusMessages(List<JobStatusMessage> jobs) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        sortJobStatusMessages(jobs)
                .forEachOrdered(
                        job ->
                                System.out.println(
                                        dateFormat.format(new Date(job.getStartTime()))
                                                + " : "
                                                + job.getJobId()
                                                + " : "
                                                + job.getJobName()
                                                + " ("
                                                + job.getJobState()
                                                + ")"));
    }

    @VisibleForTesting
    static Stream<JobStatusMessage> sortJobStatusMessages(List<JobStatusMessage> jobs) {
        Comparator<Map.Entry<JobStatus, List<JobStatusMessage>>> statusComparator =
                (o1, o2) ->
                        String.CASE_INSENSITIVE_ORDER.compare(
                                o1.getKey().toString(), o2.getKey().toString());

        Map<JobStatus, List<JobStatusMessage>> jobsByState =
                jobs.stream().collect(Collectors.groupingBy(JobStatusMessage::getJobState));
        return jobsByState.entrySet().stream()
                .sorted(statusComparator)
                .map(Map.Entry::getValue)
                .flatMap(List::stream)
                .sorted(Comparator.comparing(JobStatusMessage::getStartTime));
    }

    /**
     * Executes the STOP action.
     *
     * @param args Command line arguments for the stop action.
     */
    protected void stop(String[] args) throws Exception {
        LOG.info("Running 'stop-with-savepoint' command.");

        final Options commandOptions = CliFrontendParser.getStopCommandOptions();
        final CommandLine commandLine = getCommandLine(commandOptions, args, false);

        final StopOptions stopOptions = new StopOptions(commandLine);
        if (stopOptions.isPrintHelp()) {
            CliFrontendParser.printHelpForStop(customCommandLines);
            return;
        }

        final String[] cleanedArgs = stopOptions.getArgs();

        final String targetDirectory =
                stopOptions.hasSavepointFlag() && cleanedArgs.length > 0
                        ? stopOptions.getTargetDirectory()
                        : null; // the default savepoint location is going to be used in this case.

        final JobID jobId =
                cleanedArgs.length != 0
                        ? parseJobId(cleanedArgs[0])
                        : parseJobId(stopOptions.getTargetDirectory());

        final boolean advanceToEndOfEventTime = stopOptions.shouldAdvanceToEndOfEventTime();

        final SavepointFormatType formatType = stopOptions.getFormatType();
        logAndSysout(
                (advanceToEndOfEventTime ? "Draining job " : "Suspending job ")
                        + "\""
                        + jobId
                        + "\" with a "
                        + formatType
                        + " savepoint.");

        final CustomCommandLine activeCommandLine = validateAndGetActiveCommandLine(commandLine);

        runClusterAction(
                activeCommandLine,
                commandLine,
                (clusterClient, effectiveConfiguration) -> {
                    // Trigger savepoint in detached mode
                    if (stopOptions.isDetached()) {
                        // trigger stop-with-savepoint in detached mode and
                        // return the trigger id immediately
                        stopWithDetachedSavepoint(
                                clusterClient,
                                jobId,
                                advanceToEndOfEventTime,
                                targetDirectory,
                                formatType,
                                getClientTimeout(effectiveConfiguration));
                    } else {
                        stopWithSavepoint(
                                clusterClient,
                                jobId,
                                advanceToEndOfEventTime,
                                targetDirectory,
                                formatType,
                                getClientTimeout(effectiveConfiguration));
                    }
                });
    }

    /**
     * Executes the CANCEL action.
     *
     * @param args Command line arguments for the cancel action.
     */
    protected void cancel(String[] args) throws Exception {
        LOG.info("Running 'cancel' command.");

        final Options commandOptions = CliFrontendParser.getCancelCommandOptions();
        final CommandLine commandLine = getCommandLine(commandOptions, args, false);

        CancelOptions cancelOptions = new CancelOptions(commandLine);

        // evaluate help flag
        if (cancelOptions.isPrintHelp()) {
            CliFrontendParser.printHelpForCancel(customCommandLines);
            return;
        }

        final CustomCommandLine activeCommandLine = validateAndGetActiveCommandLine(commandLine);

        final String[] cleanedArgs = cancelOptions.getArgs();

        if (cancelOptions.isWithSavepoint()) {

            logAndSysout(
                    "DEPRECATION WARNING: Cancelling a job with savepoint is deprecated. Use \"stop\" instead.");

            final JobID jobId;
            final String targetDirectory;

            if (cleanedArgs.length > 0) {
                jobId = parseJobId(cleanedArgs[0]);
                targetDirectory = cancelOptions.getSavepointTargetDirectory();
            } else {
                jobId = parseJobId(cancelOptions.getSavepointTargetDirectory());
                targetDirectory = null;
            }

            final SavepointFormatType formatType = cancelOptions.getFormatType();
            if (targetDirectory == null) {
                logAndSysout(
                        "Cancelling job "
                                + jobId
                                + " with "
                                + formatType
                                + " savepoint to default savepoint directory.");
            } else {
                logAndSysout(
                        "Cancelling job "
                                + jobId
                                + " with "
                                + formatType
                                + " savepoint to "
                                + targetDirectory
                                + '.');
            }

            runClusterAction(
                    activeCommandLine,
                    commandLine,
                    (clusterClient, effectiveConfiguration) -> {
                        final String savepointPath;
                        try {
                            savepointPath =
                                    clusterClient
                                            .cancelWithSavepoint(jobId, targetDirectory, formatType)
                                            .get(
                                                    getClientTimeout(effectiveConfiguration)
                                                            .toMillis(),
                                                    TimeUnit.MILLISECONDS);
                        } catch (Exception e) {
                            throw new FlinkException("Could not cancel job " + jobId + '.', e);
                        }
                        logAndSysout(
                                "Cancelled job "
                                        + jobId
                                        + ". Savepoint stored in "
                                        + savepointPath
                                        + '.');
                    });
        } else {
            final JobID jobId;

            if (cleanedArgs.length > 0) {
                jobId = parseJobId(cleanedArgs[0]);
            } else {
                throw new CliArgsException("Missing JobID. Specify a JobID to cancel a job.");
            }

            logAndSysout("Cancelling job " + jobId + '.');

            runClusterAction(
                    activeCommandLine,
                    commandLine,
                    (clusterClient, effectiveConfiguration) -> {
                        try {
                            clusterClient
                                    .cancel(jobId)
                                    .get(
                                            getClientTimeout(effectiveConfiguration).toMillis(),
                                            TimeUnit.MILLISECONDS);
                        } catch (Exception e) {
                            throw new FlinkException("Could not cancel job " + jobId + '.', e);
                        }
                    });

            logAndSysout("Cancelled job " + jobId + '.');
        }
    }

    public CommandLine getCommandLine(
            final Options commandOptions, final String[] args, final boolean stopAtNonOptions)
            throws CliArgsException {
        final Options commandLineOptions =
                CliFrontendParser.mergeOptions(commandOptions, customCommandLineOptions);
        return CliFrontendParser.parse(commandLineOptions, args, stopAtNonOptions);
    }

    /**
     * Executes the SAVEPOINT action.
     *
     * @param args Command line arguments for the savepoint action.
     */
    protected void savepoint(String[] args) throws Exception {
        LOG.info("Running 'savepoint' command.");

        final Options commandOptions = CliFrontendParser.getSavepointCommandOptions();

        final CommandLine commandLine = getCommandLine(commandOptions, args, false);

        final SavepointOptions savepointOptions = new SavepointOptions(commandLine);

        // evaluate help flag
        if (savepointOptions.isPrintHelp()) {
            CliFrontendParser.printHelpForSavepoint(customCommandLines);
            return;
        }

        final CustomCommandLine activeCommandLine = validateAndGetActiveCommandLine(commandLine);

        if (savepointOptions.isDispose()) {
            runClusterAction(
                    activeCommandLine,
                    commandLine,
                    (clusterClient, effectiveConfiguration) ->
                            disposeSavepoint(
                                    clusterClient,
                                    savepointOptions.getSavepointPath(),
                                    getClientTimeout(effectiveConfiguration)));
        } else {
            String[] cleanedArgs = savepointOptions.getArgs();

            final JobID jobId;

            if (cleanedArgs.length >= 1) {
                String jobIdString = cleanedArgs[0];

                jobId = parseJobId(jobIdString);
            } else {
                throw new CliArgsException(
                        "Missing JobID. " + "Specify a Job ID to trigger a savepoint.");
            }

            final String savepointDirectory;
            if (cleanedArgs.length >= 2) {
                savepointDirectory = cleanedArgs[1];
            } else {
                savepointDirectory = null;
            }

            // Print superfluous arguments
            if (cleanedArgs.length >= 3) {
                logAndSysout(
                        "Provided more arguments than required. Ignoring not needed arguments.");
            }

            runClusterAction(
                    activeCommandLine,
                    commandLine,
                    (clusterClient, effectiveConfiguration) -> {
                        // Trigger savepoint in detached mode
                        if (savepointOptions.isDetached()) {
                            // trigger savepoint in detached mode and
                            // return the trigger id immediately
                            triggerDetachedSavepoint(
                                    clusterClient,
                                    jobId,
                                    savepointDirectory,
                                    savepointOptions.getFormatType(),
                                    getClientTimeout(effectiveConfiguration));
                        } else {
                            triggerSavepoint(
                                    clusterClient,
                                    jobId,
                                    savepointDirectory,
                                    savepointOptions.getFormatType(),
                                    getClientTimeout(effectiveConfiguration));
                        }
                    });
        }
    }

    /** Sends a SavepointTriggerMessage to the job manager. */
    private void stopWithSavepoint(
            ClusterClient<?> clusterClient,
            JobID jobId,
            boolean advanceToEndOfEventTime,
            String targetDirectory,
            SavepointFormatType formatType,
            Duration clientTimeout)
            throws FlinkException {
        logAndSysout("Triggering stop-with-savepoint for job " + jobId + '.');

        CompletableFuture<String> savepointPathFuture =
                clusterClient.stopWithSavepoint(
                        jobId, advanceToEndOfEventTime, targetDirectory, formatType);

        logAndSysout("Waiting for response...");

        try {
            final String savepointPath =
                    savepointPathFuture.get(clientTimeout.toMillis(), TimeUnit.MILLISECONDS);

            logAndSysout("Savepoint completed. Path: " + savepointPath);
        } catch (Exception e) {
            throw new FlinkException("Could not stop with a savepoint job \"" + jobId + "\".", e);
        }
    }

    /** Sends a SavepointTriggerMessage to the job manager in detached mode. */
    private void stopWithDetachedSavepoint(
            ClusterClient<?> clusterClient,
            JobID jobId,
            boolean advanceToEndOfEventTime,
            String targetDirectory,
            SavepointFormatType formatType,
            Duration clientTimeout)
            throws FlinkException {
        logAndSysout("Triggering stop-with-savepoint in detached mode for job " + jobId + '.');
        try {
            final String triggerId =
                    clusterClient
                            .stopWithDetachedSavepoint(
                                    jobId, advanceToEndOfEventTime, targetDirectory, formatType)
                            .get(clientTimeout.toMillis(), TimeUnit.MILLISECONDS);
            logAndSysout(
                    "Successfully trigger stop-with-savepoint in detached mode, triggerId: "
                            + triggerId);
        } catch (Exception e) {
            throw new FlinkException(
                    "Could not stop with a detached savepoint job \"" + jobId + "\".", e);
        }
    }

    /** Sends a SavepointTriggerMessage to the job manager. */
    private void triggerSavepoint(
            ClusterClient<?> clusterClient,
            JobID jobId,
            String savepointDirectory,
            SavepointFormatType formatType,
            Duration clientTimeout)
            throws FlinkException {
        logAndSysout("Triggering savepoint for job " + jobId + '.');

        CompletableFuture<String> savepointPathFuture =
                clusterClient.triggerSavepoint(jobId, savepointDirectory, formatType);

        logAndSysout("Waiting for response...");

        try {
            final String savepointPath =
                    savepointPathFuture.get(clientTimeout.toMillis(), TimeUnit.MILLISECONDS);

            logAndSysout("Savepoint completed. Path: " + savepointPath);
            logAndSysout("You can resume your program from this savepoint with the run command.");
        } catch (Exception e) {
            Throwable cause = ExceptionUtils.stripExecutionException(e);
            throw new FlinkException(
                    "Failed to trigger a savepoint for the job " + jobId + ".", cause);
        }
    }

    /** Sends a SavepointTriggerMessage to the job manager in detached mode. */
    private void triggerDetachedSavepoint(
            ClusterClient<?> clusterClient,
            JobID jobId,
            String savepointDirectory,
            SavepointFormatType formatType,
            Duration clientTimeout)
            throws FlinkException {
        logAndSysout("Triggering savepoint in detached mode for job " + jobId + '.');

        try {
            final String triggerId =
                    clusterClient
                            .triggerDetachedSavepoint(jobId, savepointDirectory, formatType)
                            .get(clientTimeout.toMillis(), TimeUnit.MILLISECONDS);

            logAndSysout("Successfully trigger manual savepoint, triggerId: " + triggerId);
        } catch (Exception e) {
            Throwable cause = ExceptionUtils.stripExecutionException(e);
            throw new FlinkException(
                    "Triggering a detached savepoint for the job " + jobId + " failed.", cause);
        }
    }

    /** Sends a SavepointDisposalRequest to the job manager. */
    private void disposeSavepoint(
            ClusterClient<?> clusterClient, String savepointPath, Duration clientTimeout)
            throws FlinkException {
        checkNotNull(
                savepointPath,
                "Missing required argument: savepoint path. "
                        + "Usage: bin/flink savepoint -d <savepoint-path>");

        logAndSysout("Disposing savepoint '" + savepointPath + "'.");

        final CompletableFuture<Acknowledge> disposeFuture =
                clusterClient.disposeSavepoint(savepointPath);

        logAndSysout("Waiting for response...");

        try {
            disposeFuture.get(clientTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new FlinkException("Failed to dispose the savepoint '" + savepointPath + "'.", e);
        }

        logAndSysout("Savepoint '" + savepointPath + "' disposed.");
    }

    /**
     * Executes the CHECKPOINT action.
     *
     * @param args Command line arguments for the checkpoint action.
     */
    protected void checkpoint(String[] args) throws Exception {
        LOG.info("Running 'checkpoint' command.");

        final Options commandOptions = CliFrontendParser.getCheckpointCommandOptions();

        final CommandLine commandLine = getCommandLine(commandOptions, args, false);

        final CheckpointOptions checkpointOptions = new CheckpointOptions(commandLine);

        // evaluate help flag
        if (checkpointOptions.isPrintHelp()) {
            CliFrontendParser.printHelpForCheckpoint(customCommandLines);
            return;
        }

        final CustomCommandLine activeCommandLine = validateAndGetActiveCommandLine(commandLine);

        String[] cleanedArgs = checkpointOptions.getArgs();

        final JobID jobId;

        if (cleanedArgs.length >= 1) {
            String jobIdString = cleanedArgs[0];

            jobId = parseJobId(jobIdString);
        } else {
            throw new CliArgsException(
                    "Missing JobID. " + "Specify a Job ID to manipulate a checkpoint.");
        }
        runClusterAction(
                activeCommandLine,
                commandLine,
                (clusterClient, effectiveConfiguration) ->
                        triggerCheckpoint(
                                clusterClient,
                                jobId,
                                checkpointOptions.getCheckpointType(),
                                getClientTimeout(effectiveConfiguration)));
    }

    /** Sends a CheckpointTriggerMessage to the job manager. */
    private void triggerCheckpoint(
            ClusterClient<?> clusterClient,
            JobID jobId,
            CheckpointType checkpointType,
            Duration clientTimeout)
            throws FlinkException {
        logAndSysout("Triggering checkpoint for job " + jobId + '.');

        CompletableFuture<Long> checkpointFuture =
                clusterClient.triggerCheckpoint(jobId, checkpointType);

        logAndSysout("Waiting for response...");

        try {
            final long checkpointId =
                    checkpointFuture.get(clientTimeout.toMillis(), TimeUnit.MILLISECONDS);

            logAndSysout(
                    "Checkpoint"
                            + (checkpointType == CheckpointType.CONFIGURED
                                    ? ""
                                    : ("(" + checkpointType + ")"))
                            + " "
                            + checkpointId
                            + " for job "
                            + jobId
                            + " completed.");
            logAndSysout("You can resume your program from this checkpoint with the run command.");
        } catch (Exception e) {
            Throwable cause = ExceptionUtils.stripExecutionException(e);
            throw new FlinkException(
                    "Failed to trigger a checkpoint for the job " + jobId + ".", cause);
        }
    }

    // --------------------------------------------------------------------------------------------
    //  Interaction with programs and JobManager
    // --------------------------------------------------------------------------------------------

    protected void executeProgram(final Configuration configuration, final PackagedProgram program)
            throws ProgramInvocationException {
        ClientUtils.executeProgram(
                new DefaultExecutorServiceLoader(), configuration, program, false, false);
    }

    /**
     * Creates a Packaged program from the given command line options.
     *
     * @return A PackagedProgram (upon success)
     */
    PackagedProgram buildProgram(final ProgramOptions runOptions)
            throws FileNotFoundException, ProgramInvocationException, CliArgsException {
        return buildProgram(runOptions, configuration);
    }

    /**
     * Creates a Packaged program from the given command line options and the
     * effectiveConfiguration.
     *
     * @return A PackagedProgram (upon success)
     */
    PackagedProgram buildProgram(final ProgramOptions runOptions, final Configuration configuration)
            throws FileNotFoundException, ProgramInvocationException, CliArgsException {
        runOptions.validate();

        String[] programArgs = runOptions.getProgramArgs();
        String jarFilePath = runOptions.getJarFilePath();
        List<URL> classpaths = runOptions.getClasspaths();

        // Get assembler class
        String entryPointClass = runOptions.getEntryPointClassName();
        File jarFile = jarFilePath != null ? getJarFile(jarFilePath) : null;

        return PackagedProgram.newBuilder()
                .setJarFile(jarFile)
                .setUserClassPaths(classpaths)
                .setEntryPointClassName(entryPointClass)
                .setConfiguration(configuration)
                .setSavepointRestoreSettings(runOptions.getSavepointRestoreSettings())
                .setArguments(programArgs)
                .build();
    }

    /**
     * Gets the JAR file from the path.
     *
     * @param jarFilePath The path of JAR file
     * @return The JAR file
     * @throws FileNotFoundException The JAR file does not exist.
     */
    private File getJarFile(String jarFilePath) throws FileNotFoundException {
        File jarFile = new File(jarFilePath);
        // Check if JAR file exists
        if (!jarFile.exists()) {
            throw new FileNotFoundException("JAR file does not exist: " + jarFile);
        } else if (!jarFile.isFile()) {
            throw new FileNotFoundException("JAR file is not a file: " + jarFile);
        }
        return jarFile;
    }

    // --------------------------------------------------------------------------------------------
    //  Logging and Exception Handling
    // --------------------------------------------------------------------------------------------

    /**
     * Displays an exception message for incorrect command line arguments.
     *
     * @param e The exception to display.
     * @return The return code for the process.
     */
    private static int handleArgException(CliArgsException e) {
        LOG.error("Invalid command line arguments.", e);

        System.out.println(e.getMessage());
        System.out.println();
        System.out.println("Use the help option (-h or --help) to get help on the command.");
        return 1;
    }

    /**
     * Displays an optional exception message for incorrect program parametrization.
     *
     * @param e The exception to display.
     * @return The return code for the process.
     */
    private static int handleParametrizationException(ProgramParametrizationException e) {
        LOG.error("Program has not been parametrized properly.", e);
        System.err.println(e.getMessage());
        return 1;
    }

    /**
     * Displays a message for a program without a job to execute.
     *
     * @return The return code for the process.
     */
    private static int handleMissingJobException() {
        System.err.println();
        System.err.println(
                "The program didn't contain a Flink job. "
                        + "Perhaps you forgot to call execute() on the execution environment.");
        return 1;
    }

    /**
     * Displays an exception message.
     *
     * @param t The exception to display.
     * @return The return code for the process.
     */
    private static int handleError(Throwable t) {
        LOG.error("Error while running the command.", t);

        System.err.println();
        System.err.println("------------------------------------------------------------");
        System.err.println(" The program finished with the following exception:");
        System.err.println();

        if (t.getCause() instanceof InvalidProgramException) {
            System.err.println(t.getCause().getMessage());
            StackTraceElement[] trace = t.getCause().getStackTrace();
            for (StackTraceElement ele : trace) {
                System.err.println("\t" + ele);
                if (ele.getMethodName().equals("main")) {
                    break;
                }
            }
        } else {
            t.printStackTrace();
        }
        return 1;
    }

    private static void logAndSysout(String message) {
        LOG.info(message);
        System.out.println(message);
    }

    // --------------------------------------------------------------------------------------------
    //  Internal methods
    // --------------------------------------------------------------------------------------------

    private JobID parseJobId(String jobIdString) throws CliArgsException {
        if (jobIdString == null) {
            throw new CliArgsException("Missing JobId");
        }

        final JobID jobId;
        try {
            jobId = JobID.fromHexString(jobIdString);
        } catch (IllegalArgumentException e) {
            throw new CliArgsException(e.getMessage());
        }
        return jobId;
    }

    /**
     * Retrieves the {@link ClusterClient} from the given {@link CustomCommandLine} and runs the
     * given {@link ClusterAction} against it.
     *
     * @param activeCommandLine to create the {@link ClusterDescriptor} from
     * @param commandLine containing the parsed command line options
     * @param clusterAction the cluster action to run against the retrieved {@link ClusterClient}.
     * @param <ClusterID> type of the cluster id
     * @throws FlinkException if something goes wrong
     */
    private <ClusterID> void runClusterAction(
            CustomCommandLine activeCommandLine,
            CommandLine commandLine,
            ClusterAction<ClusterID> clusterAction)
            throws FlinkException {
        final Configuration effectiveConfiguration =
                getEffectiveConfiguration(activeCommandLine, commandLine);
        LOG.debug(
                "Effective configuration after Flink conf, and custom commandline: {}",
                effectiveConfiguration);

        final ClusterClientFactory<ClusterID> clusterClientFactory =
                clusterClientServiceLoader.getClusterClientFactory(effectiveConfiguration);

        final ClusterID clusterId = clusterClientFactory.getClusterId(effectiveConfiguration);
        if (clusterId == null) {
            throw new FlinkException(
                    "No cluster id was specified. Please specify a cluster to which you would like to connect.");
        }

        try (final ClusterDescriptor<ClusterID> clusterDescriptor =
                clusterClientFactory.createClusterDescriptor(effectiveConfiguration)) {
            try (final ClusterClient<ClusterID> clusterClient =
                    clusterDescriptor.retrieve(clusterId).getClusterClient()) {
                clusterAction.runAction(clusterClient, effectiveConfiguration);
            }
        }
    }

    /**
     * Internal interface to encapsulate cluster actions which are executed via the {@link
     * ClusterClient}.
     *
     * @param <ClusterID> type of the cluster id
     */
    @FunctionalInterface
    private interface ClusterAction<ClusterID> {

        /**
         * Run the cluster action with the given {@link ClusterClient}.
         *
         * @param clusterClient to run the cluster action against
         * @param effectiveConfiguration Flink effective configuration
         * @throws FlinkException if something goes wrong
         */
        void runAction(ClusterClient<ClusterID> clusterClient, Configuration effectiveConfiguration)
                throws FlinkException;
    }

    // --------------------------------------------------------------------------------------------
    //  Entry point for executable
    // --------------------------------------------------------------------------------------------

    /**
     * Parses the command line arguments and starts the requested action.
     *
     * @param args command line arguments of the client.
     * @return The return code of the program
     */
    public int parseAndRun(String[] args) {

        // check for action
        if (args.length < 1) {
            CliFrontendParser.printHelp(customCommandLines);
            System.out.println("Please specify an action.");
            return 1;
        }

        // get action
        String action = args[0];

        // remove action from parameters
        final String[] params = Arrays.copyOfRange(args, 1, args.length);

        try {
            // do action
            switch (action) {
                case ACTION_RUN:
                    run(params);
                    return 0;
                case ACTION_LIST:
                    list(params);
                    return 0;
                case ACTION_INFO:
                    info(params);
                    return 0;
                case ACTION_CANCEL:
                    cancel(params);
                    return 0;
                case ACTION_STOP:
                    stop(params);
                    return 0;
                case ACTION_SAVEPOINT:
                    savepoint(params);
                    return 0;
                case ACTION_CHECKPOINT:
                    checkpoint(params);
                    return 0;
                case "-h":
                case "--help":
                    CliFrontendParser.printHelp(customCommandLines);
                    return 0;
                case "-v":
                case "--version":
                    String version = EnvironmentInformation.getVersion();
                    String commitID = EnvironmentInformation.getRevisionInformation().commitId;
                    System.out.print("Version: " + version);
                    System.out.println(
                            commitID.equals(EnvironmentInformation.UNKNOWN)
                                    ? ""
                                    : ", Commit ID: " + commitID);
                    return 0;
                default:
                    System.out.printf("\"%s\" is not a valid action.\n", action);
                    System.out.println();
                    System.out.println(
                            "Valid actions are \"run\", \"list\", \"info\", \"savepoint\", \"stop\", or \"cancel\".");
                    System.out.println();
                    System.out.println(
                            "Specify the version option (-v or --version) to print Flink version.");
                    System.out.println();
                    System.out.println(
                            "Specify the help option (-h or --help) to get help on the command.");
                    return 1;
            }
        } catch (CliArgsException ce) {
            return handleArgException(ce);
        } catch (ProgramParametrizationException ppe) {
            return handleParametrizationException(ppe);
        } catch (ProgramMissingJobException pmje) {
            return handleMissingJobException();
        } catch (Exception e) {
            return handleError(e);
        }
    }

    /** Submits the job based on the arguments. */
    public static void main(final String[] args) {
        int retCode = INITIAL_RET_CODE;
        try {
            retCode = mainInternal(args);
        } finally {
            System.exit(retCode);
        }
    }

    @VisibleForTesting
    static int mainInternal(final String[] args) {
        EnvironmentInformation.logEnvironmentInfo(LOG, "Command Line Client", args);

        // 1. find the configuration directory
        final String configurationDirectory = getConfigurationDirectoryFromEnv();

        // 2. load the global configuration
        final Configuration configuration =
                GlobalConfiguration.loadConfiguration(configurationDirectory);

        // 3. load the custom command lines
        final List<CustomCommandLine> customCommandLines =
                loadCustomCommandLines(configuration, configurationDirectory);

        int retCode = INITIAL_RET_CODE;
        try {
            final CliFrontend cli = new CliFrontend(configuration, customCommandLines);
            CommandLine commandLine =
                    cli.getCommandLine(
                            new Options(),
                            Arrays.copyOfRange(args, min(args.length, 1), args.length),
                            true);
            Configuration securityConfig = new Configuration(cli.configuration);
            DynamicPropertiesUtil.encodeDynamicProperties(commandLine, securityConfig);
            SecurityUtils.install(new SecurityConfiguration(securityConfig));
            retCode = SecurityUtils.getInstalledContext().runSecured(() -> cli.parseAndRun(args));
        } catch (Throwable t) {
            final Throwable strippedThrowable =
                    ExceptionUtils.stripException(t, UndeclaredThrowableException.class);
            LOG.error("Fatal error while running command line interface.", strippedThrowable);
            strippedThrowable.printStackTrace();
        }
        return retCode;
    }

    // --------------------------------------------------------------------------------------------
    //  Miscellaneous Utilities
    // --------------------------------------------------------------------------------------------

    public static String getConfigurationDirectoryFromEnv() {
        String location = System.getenv(ConfigConstants.ENV_FLINK_CONF_DIR);

        if (location != null) {
            if (new File(location).exists()) {
                return location;
            } else {
                throw new RuntimeException(
                        "The configuration directory '"
                                + location
                                + "', specified in the '"
                                + ConfigConstants.ENV_FLINK_CONF_DIR
                                + "' environment variable, does not exist.");
            }
        } else if (new File(CONFIG_DIRECTORY_FALLBACK_1).exists()) {
            location = CONFIG_DIRECTORY_FALLBACK_1;
        } else if (new File(CONFIG_DIRECTORY_FALLBACK_2).exists()) {
            location = CONFIG_DIRECTORY_FALLBACK_2;
        } else {
            throw new RuntimeException(
                    "The configuration directory was not specified. "
                            + "Please specify the directory containing the configuration file through the '"
                            + ConfigConstants.ENV_FLINK_CONF_DIR
                            + "' environment variable.");
        }
        return location;
    }

    /**
     * Writes the given job manager address to the associated configuration object.
     *
     * @param address Address to write to the configuration
     * @param config The configuration to write to
     */
    static void setJobManagerAddressInConfig(Configuration config, InetSocketAddress address) {
        config.set(JobManagerOptions.ADDRESS, address.getHostString());
        config.set(JobManagerOptions.PORT, address.getPort());
        config.set(RestOptions.ADDRESS, address.getHostString());
        config.set(RestOptions.PORT, address.getPort());
    }

    public static List<CustomCommandLine> loadCustomCommandLines(
            Configuration configuration, String configurationDirectory) {
        List<CustomCommandLine> customCommandLines = new ArrayList<>();
        customCommandLines.add(new GenericCLI(configuration, configurationDirectory));

        //	Command line interface of the YARN session, with a special initialization here
        //	to prefix all options with y/yarn.
        final String flinkYarnSessionCLI = "org.apache.flink.yarn.cli.FlinkYarnSessionCli";
        try {
            customCommandLines.add(
                    loadCustomCommandLine(
                            flinkYarnSessionCLI,
                            configuration,
                            configurationDirectory,
                            "y",
                            "yarn"));
        } catch (NoClassDefFoundError | Exception e) {
            final String errorYarnSessionCLI = "org.apache.flink.yarn.cli.FallbackYarnSessionCli";
            try {
                LOG.info("Loading FallbackYarnSessionCli");
                customCommandLines.add(loadCustomCommandLine(errorYarnSessionCLI, configuration));
            } catch (Exception exception) {
                LOG.warn("Could not load CLI class {}.", flinkYarnSessionCLI, e);
            }
        }

        //	Tips: DefaultCLI must be added at last, because getActiveCustomCommandLine(..) will get
        // the
        //	      active CustomCommandLine in order and DefaultCLI isActive always return true.
        customCommandLines.add(new DefaultCLI());

        return customCommandLines;
    }

    // --------------------------------------------------------------------------------------------
    //  Custom command-line
    // --------------------------------------------------------------------------------------------

    /**
     * Gets the custom command-line for the arguments.
     *
     * @param commandLine The input to the command-line.
     * @return custom command-line which is active (may only be one at a time)
     */
    public CustomCommandLine validateAndGetActiveCommandLine(CommandLine commandLine) {
        LOG.debug("Custom commandlines: {}", customCommandLines);
        for (CustomCommandLine cli : customCommandLines) {
            LOG.debug(
                    "Checking custom commandline {}, isActive: {}", cli, cli.isActive(commandLine));
            if (cli.isActive(commandLine)) {
                return cli;
            }
        }
        throw new IllegalStateException("No valid command-line found.");
    }

    /**
     * Loads a class from the classpath that implements the CustomCommandLine interface.
     *
     * @param className The fully-qualified class name to load.
     * @param params The constructor parameters
     */
    private static CustomCommandLine loadCustomCommandLine(String className, Object... params)
            throws Exception {

        Class<? extends CustomCommandLine> customCliClass =
                Class.forName(className).asSubclass(CustomCommandLine.class);

        // construct class types from the parameters
        Class<?>[] types = new Class<?>[params.length];
        for (int i = 0; i < params.length; i++) {
            checkNotNull(params[i], "Parameters for custom command-lines may not be null.");
            types[i] = params[i].getClass();
        }

        Constructor<? extends CustomCommandLine> constructor = customCliClass.getConstructor(types);

        return constructor.newInstance(params);
    }

    /**
     * Get client timeout from command line via effective configuration.
     *
     * @param effectiveConfiguration Flink effective configuration.
     * @return client timeout with Duration type
     */
    private Duration getClientTimeout(Configuration effectiveConfiguration) {
        return effectiveConfiguration.get(ClientOptions.CLIENT_TIMEOUT);
    }

    /**
     * Get default parallelism from command line via effective configuration.
     *
     * @param effectiveConfiguration Flink effective configuration.
     * @return default parallelism.
     */
    private int getDefaultParallelism(Configuration effectiveConfiguration) {
        return effectiveConfiguration.get(CoreOptions.DEFAULT_PARALLELISM);
    }
}
