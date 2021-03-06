/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.metrics.MetricsTag;
import com.uber.cadence.internal.metrics.NoopScope;
import com.uber.cadence.internal.replay.DeciderCache;
import com.uber.cadence.internal.sync.SyncActivityWorker;
import com.uber.cadence.internal.sync.SyncWorkflowWorker;
import com.uber.cadence.internal.worker.Dispatcher;
import com.uber.cadence.internal.worker.PollDecisionTaskDispatcherFactory;
import com.uber.cadence.internal.worker.Poller;
import com.uber.cadence.internal.worker.PollerOptions;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.cadence.internal.worker.WorkflowPollTaskFactory;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.WorkflowMethod;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hosts activity and workflow implementations. Uses long poll to receive activity and decision
 * tasks and processes them in a correspondent thread pool.
 */
public final class Worker {

  private final WorkerOptions options;
  private final String taskList;
  private final SyncWorkflowWorker workflowWorker;
  private final SyncActivityWorker activityWorker;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final DeciderCache cache;
  private final String stickyTaskListName;
  private ThreadPoolExecutor threadPoolExecutor;

  /**
   * Creates worker that connects to an instance of the Cadence Service.
   *
   * @param service client to the Cadence Service endpoint.
   * @param domain domain that worker uses to poll.
   * @param taskList task list name worker uses to poll. It uses this name for both decision and
   *     activity task list polls.
   * @param options Options (like {@link DataConverter} override) for configuring worker.
   * @param stickyTaskListName
   */
  private Worker(
      IWorkflowService service,
      String domain,
      String taskList,
      WorkerOptions options,
      DeciderCache cache,
      String stickyTaskListName,
      Duration stickyDecisionScheduleToStartTimeout,
      ThreadPoolExecutor threadPoolExecutor) {

    Objects.requireNonNull(service, "service should not be null");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(domain), "domain should not be an empty string");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(taskList), "taskList should not be an empty string");
    this.cache = cache;
    this.stickyTaskListName = stickyTaskListName;
    this.threadPoolExecutor = Objects.requireNonNull(threadPoolExecutor);

    this.taskList = taskList;
    this.options = MoreObjects.firstNonNull(options, new WorkerOptions.Builder().build());

    SingleWorkerOptions activityOptions = toActivityOptions(this.options, domain, taskList);
    activityWorker =
        this.options.isDisableActivityWorker()
            ? null
            : new SyncActivityWorker(service, domain, taskList, activityOptions);

    SingleWorkerOptions workflowOptions = toWorkflowOptions(this.options, domain, taskList);
    workflowWorker =
        this.options.isDisableWorkflowWorker()
            ? null
            : new SyncWorkflowWorker(
                service,
                domain,
                taskList,
                this.options.getInterceptorFactory(),
                workflowOptions,
                this.cache,
                this.stickyTaskListName,
                stickyDecisionScheduleToStartTimeout,
                this.threadPoolExecutor);
  }

  private static SingleWorkerOptions toActivityOptions(
      WorkerOptions options, String domain, String taskList) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, domain)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    return new SingleWorkerOptions.Builder()
        .setDataConverter(options.getDataConverter())
        .setIdentity(options.getIdentity())
        .setPollerOptions(options.getActivityPollerOptions())
        .setReportCompletionRetryOptions(options.getReportActivityCompletionRetryOptions())
        .setReportFailureRetryOptions(options.getReportActivityFailureRetryOptions())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentActivityExecutionSize())
        .setMetricsScope(options.getMetricsScope().tagged(tags))
        .setEnableLoggingInReplay(options.getEnableLoggingInReplay())
        .build();
  }

  private static SingleWorkerOptions toWorkflowOptions(
      WorkerOptions options, String domain, String taskList) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, domain)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    return new SingleWorkerOptions.Builder()
        .setDataConverter(options.getDataConverter())
        .setIdentity(options.getIdentity())
        .setPollerOptions(options.getWorkflowPollerOptions())
        .setReportCompletionRetryOptions(options.getReportWorkflowCompletionRetryOptions())
        .setReportFailureRetryOptions(options.getReportWorkflowFailureRetryOptions())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentWorklfowExecutionSize())
        .setMetricsScope(options.getMetricsScope().tagged(tags))
        .setEnableLoggingInReplay(options.getEnableLoggingInReplay())
        .build();
  }

  /**
   * Register workflow implementation classes with a worker. Overwrites previously registered types.
   * A workflow implementation class must implement at least one interface with a method annotated
   * with {@link WorkflowMethod}. That method becomes a workflow type that this worker supports.
   *
   * <p>Implementations that share a worker must implement different interfaces as a workflow type
   * is identified by the workflow interface, not by the implementation.
   *
   * <p>The reason for registration accepting workflow class, but not the workflow instance is that
   * workflows are stateful and a new instance is created for each workflow execution.
   */
  public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationClasses) {
    Preconditions.checkState(
        workflowWorker != null,
        "registerWorkflowImplementationTypes is not allowed when disableWorkflowWorker is set in worker options");
    Preconditions.checkState(
        !started.get(),
        "registerWorkflowImplementationTypes is not allowed after worker has started");

    workflowWorker.setWorkflowImplementationTypes(workflowImplementationClasses);
  }

  /**
   * Configures a factory to use when an instance of a workflow implementation is created. The only
   * valid use for this method is unit testing, specifically to instantiate mocks that implement
   * child workflows. An example of mocking a child workflow:
   *
   * <pre><code>
   *   worker.addWorkflowImplementationFactory(ChildWorkflow.class, () -> {
   *     ChildWorkflow child = mock(ChildWorkflow.class);
   *     when(child.workflow(anyString(), anyString())).thenReturn("result1");
   *     return child;
   *   });
   * </code></pre>
   *
   * <p>Unless mocking a workflow execution use {@link
   * #registerWorkflowImplementationTypes(Class[])}.
   *
   * @param workflowInterface Workflow interface that this factory implements
   * @param factory factory that when called creates a new instance of the workflow implementation
   *     object.
   * @param <R> type of the workflow object to create.
   */
  @VisibleForTesting
  public <R> void addWorkflowImplementationFactory(Class<R> workflowInterface, Func<R> factory) {
    workflowWorker.addWorkflowImplementationFactory(workflowInterface, factory);
  }

  /**
   * Register activity implementation objects with a worker. Overwrites previously registered
   * objects. As activities are reentrant and stateless only one instance per activity type is
   * registered.
   *
   * <p>Implementations that share a worker must implement different interfaces as an activity type
   * is identified by the activity interface, not by the implementation.
   *
   * <p>
   */
  public void registerActivitiesImplementations(Object... activityImplementations) {
    Preconditions.checkState(
        activityWorker != null,
        "registerActivitiesImplementations is not allowed when disableWorkflowWorker is set in worker options");
    Preconditions.checkState(
        !started.get(),
        "registerActivitiesImplementations is not allowed after worker has started");

    activityWorker.setActivitiesImplementation(activityImplementations);
  }

  private void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    if (workflowWorker != null) {
      workflowWorker.start();
    }
    if (activityWorker != null) {
      activityWorker.start();
    }
  }

  public boolean isStarted() {
    return started.get();
  }

  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Shutdown a worker, waiting for activities to complete execution up to the specified timeout.
   */
  private void shutdown(Duration timeout) throws InterruptedException {
    long time = System.currentTimeMillis();
    if (activityWorker != null) {
      activityWorker.shutdownAndAwaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    if (workflowWorker != null) {
      long left = timeout.toMillis() - (System.currentTimeMillis() - time);
      workflowWorker.shutdownAndAwaitTermination(left, TimeUnit.MILLISECONDS);
    }
    closed.set(true);
  }

  @Override
  public String toString() {
    return "Worker{" + "options=" + options + '}';
  }

  /**
   * This is an utility method to query a workflow execution using this particular instance of a
   * worker. It gets a history from a Cadence service, replays a workflow code and then runs the
   * query. This method is useful to troubleshoot workflows by running them in a debugger. To work
   * the workflow implementation type must be registered with this worker. In most cases using
   * {@link WorkflowClient} to query workflows is preferable, as it doesn't require workflow
   * implementation code to be available. There is no need to call {@link #start()} to be able to
   * call this method.
   *
   * @param execution workflow execution to replay and then query locally
   * @param queryType query type to execute
   * @param returnType return type of the query result
   * @param args query arguments
   * @param <R> type of the query result
   * @return query result
   * @throws Exception if replay failed for any reason
   */
  public <R> R queryWorkflowExecution(
      WorkflowExecution execution, String queryType, Class<R> returnType, Object... args)
      throws Exception {
    return queryWorkflowExecution(execution, queryType, returnType, returnType, args);
  }

  /**
   * This is an utility method to query a workflow execution using this particular instance of a
   * worker. It gets a history from a Cadence service, replays a workflow code and then runs the
   * query. This method is useful to troubleshoot workflows by running them in a debugger. To work
   * the workflow implementation type must be registered with this worker. In most cases using
   * {@link WorkflowClient} to query workflows is preferable, as it doesn't require workflow
   * implementation code to be available. There is no need to call {@link #start()} to be able to
   * call this method.
   *
   * @param execution workflow execution to replay and then query locally
   * @param queryType query type to execute
   * @param resultClass return class of the query result
   * @param resultType return type of the query result. Useful when resultClass is a generic type.
   * @param args query arguments
   * @param <R> type of the query result
   * @return query result
   * @throws Exception if replay failed for any reason
   */
  public <R> R queryWorkflowExecution(
      WorkflowExecution execution,
      String queryType,
      Class<R> resultClass,
      Type resultType,
      Object... args)
      throws Exception {
    if (workflowWorker == null) {
      throw new IllegalStateException("disableWorkflowWorker is set in worker options");
    }
    return workflowWorker.queryWorkflowExecution(
        execution, queryType, resultClass, resultType, args);
  }

  public String getTaskList() {
    return taskList;
  }

  /** Maintains worker creation and lifecycle. */
  public static final class Factory {
    private final List<Worker> workers = new ArrayList<>();
    private final IWorkflowService workflowService;
    private final String domain;
    private final UUID id =
        UUID.randomUUID(); // Guarantee uniqueness for stickyTaskListName when multiple factories
    private final ThreadPoolExecutor workflowThreadPool;
    private final AtomicInteger workflowThreadCounter = new AtomicInteger();
    private final FactoryOptions factoryOptions;

    private Poller<PollForDecisionTaskResponse> stickyPoller;
    private Dispatcher<String, PollForDecisionTaskResponse> dispatcher;
    private DeciderCache cache;

    private State state = State.Initial;

    private final String statusErrorMessage =
        "attempted to %s while in %s state. Acceptable States: %s";
    private static final Logger log = LoggerFactory.getLogger(Factory.class);
    /**
     * Creates a factory. Workers will be connected to a local deployment of cadence-server
     *
     * @param domain Domain used by workers to poll for workflows.
     */
    public Factory(String domain) {
      this(new WorkflowServiceTChannel(), domain, null);
    }

    /**
     * Creates a factory. Workers will be connected to the cadence-server at the specific host and
     * port.
     *
     * @param host host used by the underlying workflowServiceClient to connect to.
     * @param port port used by the underlying workflowServiceClient to connect to.
     * @param domain Domain used by workers to poll for workflows.
     */
    public Factory(String host, int port, String domain) {
      this(new WorkflowServiceTChannel(host, port), domain, null);
    }

    /**
     * Creates a factory connected to a local deployment of cadence-server.
     *
     * @param domain Domain used by workers to poll for workflows.
     * @param factoryOptions Options used to configure factory settings
     */
    public Factory(String domain, FactoryOptions factoryOptions) {
      this(new WorkflowServiceTChannel(), domain, factoryOptions);
    }

    /**
     * Creates a factory. Workers will be connected to the cadence-server at the specific host and
     * port.
     *
     * @param host host used by the underlying workflowServiceClient to connect to.
     * @param port port used by the underlying workflowServiceClient to connect to.
     * @param domain Domain used by workers to poll for workflows.
     * @param factoryOptions Options used to configure factory settings
     */
    public Factory(String host, int port, String domain, FactoryOptions factoryOptions) {
      this(new WorkflowServiceTChannel(host, port), domain, factoryOptions);
    }

    /**
     * Creates a factory. Workers will be connect to the cadence-server using the workflowService
     * client passed in.
     *
     * @param workflowService client to the Cadence Service endpoint.
     * @param domain Domain used by workers to poll for workflows.
     */
    public Factory(IWorkflowService workflowService, String domain) {
      this(workflowService, domain, null);
    }

    /**
     * Creates a factory. Workers will be connect to the cadence-server using the workflowService
     * client passed in.
     *
     * @param workflowService client to the Cadence Service endpoint.
     * @param domain Domain used by workers to poll for workflows.
     * @param factoryOptions Options used to configure factory settings
     */
    public Factory(IWorkflowService workflowService, String domain, FactoryOptions factoryOptions) {
      Preconditions.checkArgument(
          !Strings.isNullOrEmpty(domain), "domain should not be an empty string");

      this.domain = domain;
      this.workflowService =
          Objects.requireNonNull(workflowService, "workflowService should not be null");

      factoryOptions =
          factoryOptions == null ? new FactoryOptions.Builder().build() : factoryOptions;
      this.factoryOptions = factoryOptions;

      workflowThreadPool =
          new ThreadPoolExecutor(
              0,
              this.factoryOptions.maxWorkflowThreadCount,
              1,
              TimeUnit.SECONDS,
              new SynchronousQueue<>());
      workflowThreadPool.setThreadFactory(
          r -> new Thread(r, "workflow-thread-" + workflowThreadCounter.incrementAndGet()));

      if (this.factoryOptions.disableStickyExecution) {
        return;
      }

      Scope metricsScope =
          this.factoryOptions.metricsScope.tagged(
              new ImmutableMap.Builder<String, String>(2)
                  .put(MetricsTag.DOMAIN, domain)
                  .put(MetricsTag.TASK_LIST, getHostName())
                  .build());

      this.cache = new DeciderCache(this.factoryOptions.cacheMaximumSize, metricsScope);

      dispatcher = new PollDecisionTaskDispatcherFactory(workflowService).create();
      stickyPoller =
          new Poller<>(
              id.toString(),
              new WorkflowPollTaskFactory(
                      workflowService, domain, getStickyTaskListName(), metricsScope, id.toString())
                  .get(),
              dispatcher,
              this.factoryOptions.stickyWorkflowPollerOptions,
              metricsScope);
    }

    /**
     * Creates worker that connects to an instance of the Cadence Service. It uses the domain
     * configured at the Factory level. New workers cannot be created after the start() has been
     * called
     *
     * @param taskList task list name worker uses to poll. It uses this name for both decision and
     *     activity task list polls.
     * @return Worker
     */
    public Worker newWorker(String taskList) {
      return newWorker(taskList, null);
    }

    /**
     * Creates worker that connects to an instance of the Cadence Service. It uses the domain
     * configured at the Factory level. New workers cannot be created after the start() has been
     * called
     *
     * @param taskList task list name worker uses to poll. It uses this name for both decision and
     *     activity task list polls.
     * @param options Options (like {@link DataConverter} override) for configuring worker.
     * @return Worker
     */
    public Worker newWorker(String taskList, WorkerOptions options) {
      Preconditions.checkArgument(
          !Strings.isNullOrEmpty(taskList), "taskList should not be an empty string");

      synchronized (this) {
        Preconditions.checkState(
            state == State.Initial,
            String.format(
                statusErrorMessage, "create new worker", state.name(), State.Initial.name()));
        Worker worker =
            new Worker(
                workflowService,
                domain,
                taskList,
                options,
                cache,
                getStickyTaskListName(),
                Duration.ofSeconds(factoryOptions.stickyDecisionScheduleToStartTimeoutInSeconds),
                workflowThreadPool);
        workers.add(worker);

        if (!this.factoryOptions.disableStickyExecution) {
          dispatcher.subscribe(taskList, worker.workflowWorker);
        }

        return worker;
      }
    }

    /** Starts all the workers created by this factory. */
    public void start() {
      synchronized (this) {
        Preconditions.checkState(
            state == State.Initial || state == State.Started,
            String.format(
                statusErrorMessage,
                "start WorkerFactory",
                state.name(),
                String.format("%s, %s", State.Initial.name(), State.Initial.name())));
        if (state == State.Started) {
          return;
        }
        state = State.Started;

        for (Worker worker : workers) {
          worker.start();
        }

        if (stickyPoller != null) {
          stickyPoller.start();
        }
      }
    }

    /**
     * Shuts down Poller used for sticky workflows and all workers created by this factory. Shutdown
     * will be called on each worker created by this factory with the given timeout. If the timeout
     * is exceeded a warning is logged.
     *
     * @param timeout
     */
    public void shutdown(Duration timeout) {
      synchronized (this) {
        state = State.Shutdown;
        if (stickyPoller != null) {
          stickyPoller.shutdown();
        }

        for (Worker worker : workers) {
          try {
            worker.shutdown(timeout);
          } catch (InterruptedException e) {
            log.warn("Interrupted exception thrown during worker shutdown.", e);
          }
        }
      }
    }

    @VisibleForTesting
    DeciderCache getCache() {
      return this.cache;
    }

    @VisibleForTesting
    String getHostName() {
      try {
        return InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        return "UnknownHost";
      }
    }

    private String getStickyTaskListName() {
      return this.factoryOptions.disableStickyExecution
          ? null
          : String.format("%s:%s", getHostName(), id);
    }

    enum State {
      Initial,
      Started,
      Shutdown
    }
  }

  public static class FactoryOptions {
    public static class Builder {
      private boolean disableStickyExecution;
      private int stickyDecisionScheduleToStartTimeoutInSeconds = 5;
      private int cacheMaximumSize = 600;
      private int maxWorkflowThreadCount = 600;
      private PollerOptions stickyWorkflowPollerOptions;
      private Scope metricScope;

      /**
       * When set to false it will create an affinity between the worker and the workflow run it's
       * processing. Workers will cache workflows and will handle all decisions for that workflow
       * instance until it's complete or evicted from the cache. Default value is false.
       */
      public Builder setDisableStickyExecution(boolean disableStickyExecution) {
        this.disableStickyExecution = disableStickyExecution;
        return this;
      }

      /**
       * When Sticky execution is enabled this will set the maximum allowed number of workflows
       * cached. This cache is shared by all workers created by the Factory. Default value is 600
       */
      public Builder setCacheMaximumSize(int cacheMaximumSize) {
        this.cacheMaximumSize = cacheMaximumSize;
        return this;
      }

      /**
       * Maximum number of threads available for workflow execution across all workers created by
       * the Factory.
       */
      public Builder setMaxWorkflowThreadCount(int maxWorkflowThreadCount) {
        this.maxWorkflowThreadCount = maxWorkflowThreadCount;
        return this;
      }

      /**
       * Timeout for sticky workflow decision to be picked up by the host assigned to it. Once it
       * times out then it can be picked up by any worker. Default value is 5 seconds.
       */
      public Builder setStickyDecisionScheduleToStartTimeoutInSeconds(
          int stickyDecisionScheduleToStartTimeoutInSeconds) {
        this.stickyDecisionScheduleToStartTimeoutInSeconds =
            stickyDecisionScheduleToStartTimeoutInSeconds;
        return this;
      }

      /**
       * PollerOptions for poller responsible for polling for decisions for workflows cached by all
       * workers created by this factory.
       */
      public Builder setStickyWorkflowPollerOptions(PollerOptions stickyWorkflowPollerOptions) {
        this.stickyWorkflowPollerOptions = stickyWorkflowPollerOptions;
        return this;
      }

      public Builder setMetricScope(Scope metricScope) {
        this.metricScope = metricScope;
        return this;
      }

      public FactoryOptions build() {
        return new FactoryOptions(
            disableStickyExecution,
            cacheMaximumSize,
            maxWorkflowThreadCount,
            stickyDecisionScheduleToStartTimeoutInSeconds,
            stickyWorkflowPollerOptions,
            metricScope);
      }
    }

    private final boolean disableStickyExecution;
    private final int cacheMaximumSize;
    private final int maxWorkflowThreadCount;
    private final int stickyDecisionScheduleToStartTimeoutInSeconds;
    private final PollerOptions stickyWorkflowPollerOptions;
    private final Scope metricsScope;

    private FactoryOptions(
        boolean disableStickyExecution,
        int cacheMaximumSize,
        int maxWorkflowThreadCount,
        int stickyDecisionScheduleToStartTimeoutInSeconds,
        PollerOptions stickyWorkflowPollerOptions,
        Scope metricsScope) {
      Preconditions.checkArgument(
          cacheMaximumSize > 0, "cacheMaximumSize should be greater than 0");
      Preconditions.checkArgument(
          maxWorkflowThreadCount > 0, "maxWorkflowThreadCount should be greater than 0");
      Preconditions.checkArgument(
          stickyDecisionScheduleToStartTimeoutInSeconds > 0,
          "stickyDecisionScheduleToStartTimeoutInSeconds should be greater than 0");

      this.disableStickyExecution = disableStickyExecution;
      this.cacheMaximumSize = cacheMaximumSize;
      this.maxWorkflowThreadCount = maxWorkflowThreadCount;
      this.stickyDecisionScheduleToStartTimeoutInSeconds =
          stickyDecisionScheduleToStartTimeoutInSeconds;

      if (stickyWorkflowPollerOptions == null) {
        this.stickyWorkflowPollerOptions =
            new PollerOptions.Builder()
                .setPollBackoffInitialInterval(Duration.ofMillis(200))
                .setPollBackoffMaximumInterval(Duration.ofSeconds(20))
                .setPollThreadCount(1)
                .build();
      } else {
        this.stickyWorkflowPollerOptions = stickyWorkflowPollerOptions;
      }

      if (metricsScope == null) {
        this.metricsScope = NoopScope.getInstance();
      } else {
        this.metricsScope = metricsScope;
      }
    }
  }
}
