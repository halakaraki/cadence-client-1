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

package com.uber.cadence.internal.replay;

import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.TimerFiredEventAttributes;
import com.uber.cadence.WorkflowExecutionSignaledEventAttributes;
import com.uber.cadence.WorkflowQuery;
import com.uber.cadence.internal.common.OptionsUtils;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.replay.HistoryHelper.DecisionEvents;
import com.uber.cadence.internal.replay.HistoryHelper.DecisionEventsIterator;
import com.uber.cadence.internal.worker.WorkflowExecutionException;
import com.uber.cadence.workflow.Functions;
import com.uber.m3.tally.Scope;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements decider that relies on replay of a worklfow code. An instance of this class is created
 * per decision.
 */
class ReplayDecider {

  private static final Logger log = LoggerFactory.getLogger(ReplayDecider.class);

  private static final int MILLION = 1000000;

  private final HistoryHelper historyHelper;

  private final DecisionsHelper decisionsHelper;

  private final DecisionContextImpl context;

  private ReplayWorkflow workflow;

  private boolean cancelRequested;

  private boolean completed;

  private WorkflowExecutionException failure;

  private long wakeUpTime;

  private Consumer<Exception> timerCancellationHandler;

  private final Scope metricsScope;

  private long wfStartTime = -1;

  ReplayDecider(
      String domain,
      ReplayWorkflow workflow,
      HistoryHelper historyHelper,
      DecisionsHelper decisionsHelper,
      Scope metricsScope,
      boolean enableLoggingInReplay) {
    this.workflow = workflow;
    this.historyHelper = historyHelper;
    this.decisionsHelper = decisionsHelper;
    this.metricsScope = metricsScope;
    PollForDecisionTaskResponse decisionTask = historyHelper.getDecisionTask();
    context =
        new DecisionContextImpl(
            decisionsHelper,
            domain,
            decisionTask,
            historyHelper.getWorkflowExecutionStartedEventAttributes(),
            enableLoggingInReplay);
    context.setMetricsScope(metricsScope);
  }

  public boolean isCancelRequested() {
    return cancelRequested;
  }

  private void handleWorkflowExecutionStarted(HistoryEvent event) throws Exception {
    workflow.start(event, context);
  }

  private void processEvent(HistoryEvent event) throws Throwable {
    EventType eventType = event.getEventType();
    switch (eventType) {
      case ActivityTaskCanceled:
        context.handleActivityTaskCanceled(event);
        break;
      case ActivityTaskCompleted:
        context.handleActivityTaskCompleted(event);
        break;
      case ActivityTaskFailed:
        context.handleActivityTaskFailed(event);
        break;
      case ActivityTaskStarted:
        decisionsHelper.handleActivityTaskStarted(event);
        break;
      case ActivityTaskTimedOut:
        context.handleActivityTaskTimedOut(event);
        break;
      case ExternalWorkflowExecutionCancelRequested:
        context.handleChildWorkflowExecutionCancelRequested(event);
        decisionsHelper.handleExternalWorkflowExecutionCancelRequested(event);
        break;
      case ChildWorkflowExecutionCanceled:
        context.handleChildWorkflowExecutionCanceled(event);
        break;
      case ChildWorkflowExecutionCompleted:
        context.handleChildWorkflowExecutionCompleted(event);
        break;
      case ChildWorkflowExecutionFailed:
        context.handleChildWorkflowExecutionFailed(event);
        break;
      case ChildWorkflowExecutionStarted:
        context.handleChildWorkflowExecutionStarted(event);
        break;
      case ChildWorkflowExecutionTerminated:
        context.handleChildWorkflowExecutionTerminated(event);
        break;
      case ChildWorkflowExecutionTimedOut:
        context.handleChildWorkflowExecutionTimedOut(event);
        break;
      case DecisionTaskCompleted:
        handleDecisionTaskCompleted(event);
        break;
      case DecisionTaskScheduled:
        // NOOP
        break;
      case DecisionTaskStarted:
        throw new IllegalArgumentException("not expected");
      case DecisionTaskTimedOut:
        // Handled in the processEvent(event)
        break;
      case ExternalWorkflowExecutionSignaled:
        context.handleExternalWorkflowExecutionSignaled(event);
        break;
      case StartChildWorkflowExecutionFailed:
        context.handleStartChildWorkflowExecutionFailed(event);
        break;
      case TimerFired:
        handleTimerFired(event);
        break;
      case WorkflowExecutionCancelRequested:
        handleWorkflowExecutionCancelRequested(event);
        break;
      case WorkflowExecutionSignaled:
        handleWorkflowExecutionSignaled(event);
        break;
      case WorkflowExecutionStarted:
        handleWorkflowExecutionStarted(event);
        break;
      case WorkflowExecutionTerminated:
        // NOOP
        break;
      case WorkflowExecutionTimedOut:
        // NOOP
        break;
      case ActivityTaskScheduled:
        decisionsHelper.handleActivityTaskScheduled(event);
        break;
      case ActivityTaskCancelRequested:
        decisionsHelper.handleActivityTaskCancelRequested(event);
        break;
      case RequestCancelActivityTaskFailed:
        decisionsHelper.handleRequestCancelActivityTaskFailed(event);
        break;
      case MarkerRecorded:
        context.handleMarkerRecorded(event);
        break;
      case WorkflowExecutionCompleted:
        break;
      case WorkflowExecutionFailed:
        break;
      case WorkflowExecutionCanceled:
        break;
      case WorkflowExecutionContinuedAsNew:
        break;
      case TimerStarted:
        decisionsHelper.handleTimerStarted(event);
        break;
      case TimerCanceled:
        context.handleTimerCanceled(event);
        break;
      case SignalExternalWorkflowExecutionInitiated:
        decisionsHelper.handleSignalExternalWorkflowExecutionInitiated(event);
        break;
      case SignalExternalWorkflowExecutionFailed:
        context.handleSignalExternalWorkflowExecutionFailed(event);
        break;
      case RequestCancelExternalWorkflowExecutionInitiated:
        decisionsHelper.handleRequestCancelExternalWorkflowExecutionInitiated(event);
        break;
      case RequestCancelExternalWorkflowExecutionFailed:
        decisionsHelper.handleRequestCancelExternalWorkflowExecutionFailed(event);
        break;
      case StartChildWorkflowExecutionInitiated:
        decisionsHelper.handleStartChildWorkflowExecutionInitiated(event);
        break;
      case CancelTimerFailed:
        decisionsHelper.handleCancelTimerFailed(event);
        break;
      case DecisionTaskFailed:
        break;
    }
  }

  private void eventLoop() {
    if (completed) {
      return;
    }
    try {
      completed = workflow.eventLoop();
    } catch (Error e) {
      throw e; // errors fail decision, not a workflow
    } catch (WorkflowExecutionException e) {
      failure = e;
      completed = true;
    } catch (CancellationException e) {
      if (!cancelRequested) {
        failure = workflow.mapUnexpectedException(e);
      }
      completed = true;
    } catch (Throwable e) {
      // can cast as Error is caught above.
      failure = workflow.mapUnexpectedException((Exception) e);
      completed = true;
    }
  }

  private void mayBeCompleteWorkflow() {
    if (completed) {
      completeWorkflow();
    } else {
      updateTimers();
    }
  }

  private void completeWorkflow() {
    if (failure != null) {
      decisionsHelper.failWorkflowExecution(failure);
      metricsScope.counter(MetricsType.WORKFLOW_FAILED_COUNTER).inc(1);
    } else if (cancelRequested) {
      decisionsHelper.cancelWorkflowExecution();
      metricsScope.counter(MetricsType.WORKFLOW_CANCELLED_COUNTER).inc(1);
    } else {
      ContinueAsNewWorkflowExecutionParameters continueAsNewOnCompletion =
          context.getContinueAsNewOnCompletion();
      if (continueAsNewOnCompletion != null) {
        decisionsHelper.continueAsNewWorkflowExecution(continueAsNewOnCompletion);
        metricsScope.counter(MetricsType.WORKFLOW_CONTINUE_AS_NEW_COUNTER).inc(1);
      } else {
        byte[] workflowOutput = workflow.getOutput();
        decisionsHelper.completeWorkflowExecution(workflowOutput);
        metricsScope.counter(MetricsType.WORKFLOW_COMPLETED_COUNTER).inc(1);
      }
    }

    if (wfStartTime != -1) {
      long nanoTime =
          TimeUnit.NANOSECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
      com.uber.m3.util.Duration d = com.uber.m3.util.Duration.ofNanos(nanoTime - wfStartTime);
      metricsScope.timer(MetricsType.WORKFLOW_E2E_LATENCY).record(d);
    }
  }

  private void updateTimers() {
    long nextWakeUpTime = workflow.getNextWakeUpTime();
    if (nextWakeUpTime == 0) {
      if (timerCancellationHandler != null) {
        timerCancellationHandler.accept(null);
        timerCancellationHandler = null;
      }
      wakeUpTime = nextWakeUpTime;
      return;
    }
    if (wakeUpTime == nextWakeUpTime && timerCancellationHandler != null) {
      return; // existing timer
    }
    long delayMilliseconds = nextWakeUpTime - context.currentTimeMillis();
    if (delayMilliseconds < 0) {
      throw new IllegalStateException("Negative delayMilliseconds=" + delayMilliseconds);
    }
    // Round up to the nearest second as we don't want to deliver a timer
    // earlier than requested.
    long delaySeconds =
        OptionsUtils.roundUpToSeconds(Duration.ofMillis(delayMilliseconds)).getSeconds();
    if (timerCancellationHandler != null) {
      timerCancellationHandler.accept(null);
      timerCancellationHandler = null;
    }
    wakeUpTime = nextWakeUpTime;
    timerCancellationHandler =
        context.createTimer(
            delaySeconds,
            (t) -> {
              // Intentionally left empty.
              // Timer ensures that decision is scheduled at the time workflow can make progress.
              // But no specific timer related action is necessary as Workflow.sleep is just a
              // Workflow.await with a time based condition.
            });
  }

  private void handleWorkflowExecutionCancelRequested(HistoryEvent event) {
    context.setCancelRequested(true);
    String cause = event.getWorkflowExecutionCancelRequestedEventAttributes().getCause();
    workflow.cancel(cause);
    cancelRequested = true;
  }

  private void handleTimerFired(HistoryEvent event) {
    TimerFiredEventAttributes attributes = event.getTimerFiredEventAttributes();
    String timerId = attributes.getTimerId();
    if (timerId.equals(DecisionsHelper.FORCE_IMMEDIATE_DECISION_TIMER)) {
      return;
    }
    context.handleTimerFired(attributes);
  }

  private void handleWorkflowExecutionSignaled(HistoryEvent event) {
    assert (event.getEventType() == EventType.WorkflowExecutionSignaled);
    final WorkflowExecutionSignaledEventAttributes signalAttributes =
        event.getWorkflowExecutionSignaledEventAttributes();
    if (completed) {
      throw new IllegalStateException("Signal received after workflow is closed.");
    }
    this.workflow.handleSignal(
        signalAttributes.getSignalName(), signalAttributes.getInput(), event.getEventId());
  }

  private void handleDecisionTaskCompleted(HistoryEvent event) {
    decisionsHelper.handleDecisionCompletion(event.getDecisionTaskCompletedEventAttributes());
  }

  void decide() throws Throwable {
    decideImpl(null);
  }

  private void decideImpl(Functions.Proc query) throws Throwable {
    try {
      DecisionEventsIterator iterator = historyHelper.getIterator();
      while (iterator.hasNext()) {
        DecisionEvents decision = iterator.next();
        context.setReplaying(decision.isReplay());
        context.setReplayCurrentTimeMilliseconds(decision);

        decisionsHelper.handleDecisionTaskStartedEvent(decision);
        // Markers must be cached first as their data is needed when processing events.
        for (HistoryEvent event : decision.getMarkers()) {
          processEvent(event);
        }
        for (HistoryEvent event : decision.getEvents()) {
          processEvent(event);
        }
        eventLoop();
        mayBeCompleteWorkflow();
        if (decision.isReplay()) {
          decisionsHelper.notifyDecisionSent();
        }
        // Updates state machines with results of the previous decisions
        for (HistoryEvent event : decision.getDecisionEvents()) {
          processEvent(event);
        }
      }
    } catch (Error e) {
      metricsScope.counter(MetricsType.DECISION_TASK_ERROR_COUNTER).inc(1);
      throw e;
    } finally {
      if (query != null) {
        query.apply();
      }
      workflow.close();
    }
  }

  DecisionsHelper getDecisionsHelper() {
    return decisionsHelper;
  }

  public byte[] query(WorkflowQuery query) throws Throwable {
    AtomicReference<byte[]> result = new AtomicReference<>();
    decideImpl(() -> result.set(workflow.query(query)));
    return result.get();
  }
}
