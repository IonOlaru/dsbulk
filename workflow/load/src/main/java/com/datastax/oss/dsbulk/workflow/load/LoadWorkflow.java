/*
 * Copyright DataStax, Inc.
 *
 * This software is subject to the below license agreement.
 * DataStax may make changes to the agreement from time to time,
 * and will post the amended terms at
 * https://www.datastax.com/terms/datastax-dse-bulk-utility-license-terms.
 */
package com.datastax.oss.dsbulk.workflow.load;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.codahale.metrics.MetricRegistry;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchableStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metrics.Metrics;
import com.datastax.oss.driver.shaded.guava.common.base.Stopwatch;
import com.datastax.oss.dsbulk.codecs.ConvertingCodecFactory;
import com.datastax.oss.dsbulk.commons.utils.StringUtils;
import com.datastax.oss.dsbulk.connectors.api.CommonConnectorFeature;
import com.datastax.oss.dsbulk.connectors.api.Connector;
import com.datastax.oss.dsbulk.connectors.api.Record;
import com.datastax.oss.dsbulk.executor.api.result.EmptyWriteResult;
import com.datastax.oss.dsbulk.executor.api.result.WriteResult;
import com.datastax.oss.dsbulk.executor.reactor.writer.ReactorBulkWriter;
import com.datastax.oss.dsbulk.workflow.api.Workflow;
import com.datastax.oss.dsbulk.workflow.commons.log.LogManager;
import com.datastax.oss.dsbulk.workflow.commons.metrics.MetricsManager;
import com.datastax.oss.dsbulk.workflow.commons.schema.RecordMapper;
import com.datastax.oss.dsbulk.workflow.commons.settings.BatchSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.CodecSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.ConnectorSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.DriverSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.EngineSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.ExecutorSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.LogSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.MonitoringSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.SchemaGenerationType;
import com.datastax.oss.dsbulk.workflow.commons.settings.SchemaSettings;
import com.datastax.oss.dsbulk.workflow.commons.settings.SettingsManager;
import com.datastax.oss.dsbulk.workflow.commons.utils.CloseableUtils;
import com.datastax.oss.dsbulk.workflow.commons.utils.ClusterInformationUtils;
import com.datastax.oss.dsbulk.workflow.commons.utils.WorkflowUtils;
import com.typesafe.config.Config;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/** The main class for load workflows. */
public class LoadWorkflow implements Workflow {

  private static final Logger LOGGER = LoggerFactory.getLogger(LoadWorkflow.class);

  private final SettingsManager settingsManager;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private String executionId;
  private Connector connector;
  private MetricsManager metricsManager;
  private LogManager logManager;
  private CqlSession session;
  private ReactorBulkWriter executor;
  private boolean batchingEnabled;
  private boolean dryRun;
  private int resourceCount;
  private int batchBufferSize;
  private int writeConcurrency;
  private Scheduler scheduler;

  private Function<Record, BatchableStatement<?>> mapper;
  private Function<Flux<BatchableStatement<?>>, Flux<Statement<?>>> batcher;
  private Function<Flux<Record>, Flux<Record>> totalItemsMonitor;
  private Function<Flux<Record>, Flux<Record>> totalItemsCounter;
  private Function<Flux<Record>, Flux<Record>> failedRecordsMonitor;
  private Function<Flux<BatchableStatement<?>>, Flux<BatchableStatement<?>>>
      failedStatementsMonitor;
  private Function<Flux<Record>, Flux<Record>> failedRecordsHandler;
  private Function<Flux<BatchableStatement<?>>, Flux<BatchableStatement<?>>>
      unmappableStatementsHandler;
  private Function<Flux<Statement<?>>, Flux<Statement<?>>> batcherMonitor;
  private Function<Flux<Void>, Flux<Void>> terminationHandler;
  private Function<Flux<WriteResult>, Flux<WriteResult>> failedWritesHandler;
  private Function<Flux<WriteResult>, Flux<Void>> resultPositionsHndler;
  private Function<Flux<WriteResult>, Flux<WriteResult>> queryWarningsHandler;
  private int numCores;

  LoadWorkflow(Config config) {
    settingsManager = new SettingsManager(config);
  }

  @Override
  public void init() throws Exception {
    settingsManager.init("LOAD", true);
    executionId = settingsManager.getExecutionId();
    LogSettings logSettings = settingsManager.getLogSettings();
    logSettings.init();
    ConnectorSettings connectorSettings = settingsManager.getConnectorSettings();
    connectorSettings.init();
    connector = connectorSettings.getConnector();
    connector.init();
    DriverSettings driverSettings = settingsManager.getDriverSettings();
    SchemaSettings schemaSettings = settingsManager.getSchemaSettings();
    BatchSettings batchSettings = settingsManager.getBatchSettings();
    ExecutorSettings executorSettings = settingsManager.getExecutorSettings();
    CodecSettings codecSettings = settingsManager.getCodecSettings();
    MonitoringSettings monitoringSettings = settingsManager.getMonitoringSettings();
    EngineSettings engineSettings = settingsManager.getEngineSettings();
    driverSettings.init(true);
    logSettings.logEffectiveSettings(
        settingsManager.getBulkLoaderConfig(), driverSettings.getDriverConfig());
    monitoringSettings.init();
    codecSettings.init();
    batchSettings.init();
    executorSettings.init();
    engineSettings.init();
    session = driverSettings.newSession(executionId);
    ClusterInformationUtils.printDebugInfoAboutCluster(session);
    schemaSettings.init(
        SchemaGenerationType.MAP_AND_WRITE,
        session,
        connector.supports(CommonConnectorFeature.INDEXED_RECORDS),
        connector.supports(CommonConnectorFeature.MAPPED_RECORDS));
    batchingEnabled = batchSettings.isBatchingEnabled();
    batchBufferSize = batchSettings.getBufferSize();
    logManager = logSettings.newLogManager(session, true);
    logManager.init();
    metricsManager =
        monitoringSettings.newMetricsManager(
            true,
            batchingEnabled,
            logManager.getOperationDirectory(),
            logSettings.getVerbosity(),
            session.getMetrics().map(Metrics::getRegistry).orElse(new MetricRegistry()),
            session.getContext().getProtocolVersion(),
            session.getContext().getCodecRegistry(),
            schemaSettings.getRowType());
    metricsManager.init();
    executor = executorSettings.newWriteExecutor(session, metricsManager.getExecutionListener());
    ConvertingCodecFactory codecFactory =
        codecSettings.createCodecFactory(
            schemaSettings.isAllowExtraFields(), schemaSettings.isAllowMissingFields());
    RecordMapper recordMapper =
        schemaSettings.createRecordMapper(session, connector.getRecordMetadata(), codecFactory);
    mapper = recordMapper::map;
    if (batchingEnabled) {
      batcher = batchSettings.newStatementBatcher(session)::batchByGroupingKey;
    }
    dryRun = engineSettings.isDryRun();
    if (dryRun) {
      LOGGER.info("Dry-run mode enabled.");
    }
    closed.set(false);
    totalItemsMonitor = metricsManager.newTotalItemsMonitor();
    failedRecordsMonitor = metricsManager.newFailedItemsMonitor();
    failedStatementsMonitor = metricsManager.newFailedItemsMonitor();
    batcherMonitor = metricsManager.newBatcherMonitor();
    totalItemsCounter = logManager.newTotalItemsCounter();
    failedRecordsHandler = logManager.newFailedRecordsHandler();
    unmappableStatementsHandler = logManager.newUnmappableStatementsHandler();
    queryWarningsHandler = logManager.newQueryWarningsHandler();
    failedWritesHandler = logManager.newFailedWritesHandler();
    resultPositionsHndler = logManager.newResultPositionsHandler();
    terminationHandler = logManager.newTerminationHandler();
    numCores = Runtime.getRuntime().availableProcessors();
    scheduler = Schedulers.newParallel(numCores, new DefaultThreadFactory("workflow"));
    // In order to keep a global number of X in-flight requests maximum, and in order to reduce lock
    // contention around the semaphore that controls this number, and given that we have N threads
    // executing requests, then each thread should strive to maintain a maximum of X / N in-flight
    // requests. If the maximum number of in-flight requests is unbounded, then we use a standard
    // concurrency constant.
    writeConcurrency =
        executorSettings.getMaxInFlight().isPresent()
            ? Math.max(Queues.XS_BUFFER_SIZE, executorSettings.getMaxInFlight().get() / numCores)
            : Queues.XS_BUFFER_SIZE;
    resourceCount = connector.estimatedResourceCount();
  }

  @Override
  public boolean execute() {
    LOGGER.debug("{} started.", this);
    metricsManager.start();
    Stopwatch timer = Stopwatch.createStarted();
    if (resourceCount >= WorkflowUtils.TPC_THRESHOLD) {
      threadPerCoreFlux();
    } else {
      parallelFlux();
    }
    timer.stop();
    metricsManager.stop();
    long seconds = timer.elapsed(SECONDS);
    if (logManager.getTotalErrors() == 0) {
      LOGGER.info("{} completed successfully in {}.", this, StringUtils.formatElapsed(seconds));
    } else {
      LOGGER.warn(
          "{} completed with {} errors in {}.",
          this,
          logManager.getTotalErrors(),
          StringUtils.formatElapsed(seconds));
    }
    return logManager.getTotalErrors() == 0;
  }

  private void threadPerCoreFlux() {
    Flux.defer(() -> connector.readByResource())
        .flatMap(
            records -> {
              Flux<BatchableStatement<?>> stmts =
                  Flux.from(records)
                      .transform(totalItemsMonitor)
                      .transform(totalItemsCounter)
                      .transform(failedRecordsMonitor)
                      .transform(failedRecordsHandler)
                      .map(mapper)
                      .transform(failedStatementsMonitor)
                      .transform(unmappableStatementsHandler);
              Flux<? extends Statement<?>> grouped;
              if (batchingEnabled) {
                grouped = stmts.window(batchBufferSize).flatMap(batcher).transform(batcherMonitor);
              } else {
                grouped = stmts;
              }
              return executeStatements(grouped).subscribeOn(scheduler);
            },
            numCores)
        .transform(terminationHandler)
        .blockLast();
  }

  private void parallelFlux() {
    Flux.defer(() -> connector.read())
        .window(batchingEnabled ? batchBufferSize : Queues.SMALL_BUFFER_SIZE)
        .flatMap(
            records -> {
              Flux<BatchableStatement<?>> stmts =
                  records
                      .transform(totalItemsMonitor)
                      .transform(totalItemsCounter)
                      .transform(failedRecordsMonitor)
                      .transform(failedRecordsHandler)
                      .map(mapper)
                      .transform(failedStatementsMonitor)
                      .transform(unmappableStatementsHandler);
              Flux<? extends Statement<?>> grouped;
              if (batchingEnabled) {
                grouped = stmts.transform(batcher).transform(batcherMonitor);
              } else {
                grouped = stmts;
              }
              return executeStatements(grouped).subscribeOn(scheduler);
            },
            numCores)
        .transform(terminationHandler)
        .blockLast();
  }

  private Flux<Void> executeStatements(Flux<? extends Statement<?>> stmts) {
    Flux<WriteResult> results;
    if (dryRun) {
      results = stmts.map(EmptyWriteResult::new);
    } else {
      results = stmts.flatMap(executor::writeReactive, writeConcurrency);
    }
    return results
        .transform(queryWarningsHandler)
        .transform(failedWritesHandler)
        .transform(resultPositionsHndler);
  }

  @Override
  public void close() throws Exception {
    if (closed.compareAndSet(false, true)) {
      LOGGER.debug("{} closing.", this);
      Exception e = CloseableUtils.closeQuietly(metricsManager, null);
      e = CloseableUtils.closeQuietly(logManager, e);
      e = CloseableUtils.closeQuietly(connector, e);
      e = CloseableUtils.closeQuietly(scheduler, e);
      e = CloseableUtils.closeQuietly(executor, e);
      e = CloseableUtils.closeQuietly(session, e);
      if (metricsManager != null) {
        metricsManager.reportFinalMetrics();
      }
      if (logManager != null) {
        logManager.reportLastLocations();
      }
      LOGGER.debug("{} closed.", this);
      if (e != null) {
        throw e;
      }
    }
  }

  @Override
  public String toString() {
    if (executionId == null) {
      return "Operation";
    } else {
      return "Operation " + executionId;
    }
  }
}