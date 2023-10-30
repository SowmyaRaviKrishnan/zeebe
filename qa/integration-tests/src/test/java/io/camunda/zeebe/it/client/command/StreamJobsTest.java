/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.client.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import io.camunda.zeebe.protocol.record.value.TenantOwned;
import io.camunda.zeebe.qa.util.actuator.JobStreamActuator;
import io.camunda.zeebe.qa.util.cluster.TestStandaloneBroker;
import io.camunda.zeebe.qa.util.jobstream.JobStreamActuatorAssert;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration.TestZeebe;
import io.camunda.zeebe.test.util.Strings;
import io.camunda.zeebe.test.util.junit.AutoCloseResources;
import io.camunda.zeebe.test.util.junit.AutoCloseResources.AutoCloseResource;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.zeebe.containers.clock.ZeebeClock;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@AutoCloseResources
@ZeebeIntegration
final class StreamJobsTest {
  @TestZeebe
  private static final TestStandaloneBroker ZEEBE =
      new TestStandaloneBroker()
          .withProperty("zeebe.clock.controlled", true)
          .withRecordingExporter(true);

  private static final ZeebeClock CLOCK;

  static {
    try {
      CLOCK = ZeebeClock.newDefaultClock(new URL("http://" + ZEEBE.monitoringAddress()));
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  @AutoCloseResource private final ZeebeClient client = ZEEBE.newClientBuilder().build();

  @BeforeEach
  void beforeEach() {
    CLOCK.resetTime();
  }

  @Test
  void shouldStreamJobs() {
    // given
    final var jobs = new ArrayList<ActivatedJob>();
    final var uniqueId = Strings.newRandomValidBpmnId();
    final var process =
        Bpmn.createExecutableProcess(uniqueId)
            .startEvent()
            .serviceTask("task01", b -> b.zeebeJobType(uniqueId))
            .serviceTask("task02", b -> b.zeebeJobType(uniqueId))
            .serviceTask("task03", b -> b.zeebeJobType(uniqueId))
            .endEvent()
            .done();
    final Consumer<ActivatedJob> jobHandler =
        job -> {
          jobs.add(job);
          client.newCompleteCommand(job).send();
        };
    deployProcess(process);

    // when
    final var now = Instant.now();
    CLOCK.pinTime(now);
    final var stream =
        client
            .newStreamJobsCommand()
            .jobType(uniqueId)
            .consumer(jobHandler)
            .workerName("streamer")
            .fetchVariables("foo")
            .tenantIds(TenantOwned.DEFAULT_TENANT_IDENTIFIER)
            .timeout(Duration.ofSeconds(5))
            .open(error -> {});
    final boolean processInstanceCompleted;

    try (stream) {
      awaitStreamRegistered(uniqueId);
      final var processInstanceKey =
          createProcessInstance(uniqueId, Map.of("foo", "bar", "baz", "buz"));
      processInstanceCompleted =
          RecordingExporter.processInstanceRecords()
              .withProcessInstanceKey(processInstanceKey)
              .limitToProcessInstanceCompleted()
              .findFirst()
              .isPresent();
    }

    // then
    assertThat(processInstanceCompleted)
        .as("all tasks were completed to complete the process instance")
        .isTrue();
    assertThat(jobs)
        .hasSize(3)
        .allSatisfy(
            job -> {
              assertThat(job.getWorker()).isEqualTo("streamer");
              assertThat(job.getDeadline()).isEqualTo(now.toEpochMilli() + 5000L);
              assertThat(job.getVariablesAsMap()).isEqualTo(Map.of("foo", "bar"));
            });
  }

  @Test
  void shouldNotInterfereWithPolling() {
    // given
    final var streamedJobs = new ArrayList<ActivatedJob>();
    final var uniqueId = Strings.newRandomValidBpmnId();
    final var process =
        Bpmn.createExecutableProcess(uniqueId)
            .startEvent()
            .serviceTask("task01", b -> b.zeebeJobType(uniqueId))
            .endEvent()
            .done();
    final Consumer<ActivatedJob> streamHandler = streamedJobs::add;
    deployProcess(process);

    // when - create a process instance and wait for the job to be created; this cannot be streamed
    // since the stream doesn't know about older jobs, but it can be polled! The second process
    // instance is guaranteed to stream, because we prefer streaming to polling
    final var firstPIKey = createProcessInstance(uniqueId);
    assertThat(
            RecordingExporter.jobRecords(JobIntent.CREATED)
                .withProcessInstanceKey(firstPIKey)
                .limit(1)
                .exists())
        .as("job for the first process was created")
        .isTrue();

    // open the stream
    final var stream =
        client
            .newStreamJobsCommand()
            .jobType(uniqueId)
            .consumer(streamHandler)
            .workerName("stream")
            .open(error -> {});
    final long secondPIKey;
    try (stream) {
      awaitStreamRegistered(uniqueId);
      secondPIKey = createProcessInstance(uniqueId);
      Awaitility.await("until job has been streamed")
          .untilAsserted(() -> assertThat(streamedJobs).hasSize(1));
    }

    // poll after the newer job was streamed, showing that polling for older jobs work
    final var polledJobs =
        client
            .newActivateJobsCommand()
            .jobType(uniqueId)
            .maxJobsToActivate(2)
            .workerName("poller")
            .send()
            .join();

    // then
    assertThat(polledJobs.getJobs())
        .first(InstanceOfAssertFactories.type(ActivatedJob.class))
        .extracting(ActivatedJob::getProcessInstanceKey)
        .isEqualTo(firstPIKey);
    assertThat(streamedJobs)
        .first(InstanceOfAssertFactories.type(ActivatedJob.class))
        .extracting(ActivatedJob::getProcessInstanceKey)
        .isEqualTo(secondPIKey);
  }

  @Test
  void shouldCompleteStreamOnGatewayClose() {
    // given
    final var uniqueId = Strings.newRandomValidBpmnId();

    // when
    final var stream =
        client
            .newStreamJobsCommand()
            .jobType(uniqueId)
            .consumer(ignored -> {})
            .workerName("stream")
            .open(error -> {});
    awaitStreamRegistered(uniqueId);
    ZEEBE.stop().start().awaitCompleteTopology();

    // then
    assertThat((Future<?>) stream)
        .failsWithin(Duration.ofSeconds(10))
        .withThrowableThat()
        .havingCause()
        .asInstanceOf(InstanceOfAssertFactories.throwable(StatusRuntimeException.class))
        .extracting(StatusRuntimeException::getStatus)
        .extracting(Status::getCode)
        .isEqualTo(Code.CANCELLED);
  }

  private void awaitStreamRegistered(final String jobType) {
    final var actuator = JobStreamActuator.of(ZEEBE);
    Awaitility.await("until stream with type '%s' is registered".formatted(jobType))
        .untilAsserted(
            () ->
                JobStreamActuatorAssert.assertThat(actuator)
                    .remoteStreams()
                    .haveJobType(1, jobType));
  }

  private long createProcessInstance(final String processId) {
    return createProcessInstance(processId, Map.of());
  }

  private long createProcessInstance(final String processId, final Map<String, Object> variables) {
    return client
        .newCreateInstanceCommand()
        .bpmnProcessId(processId)
        .latestVersion()
        .variables(variables)
        .send()
        .join()
        .getProcessInstanceKey();
  }

  private void deployProcess(final BpmnModelInstance process) {
    client
        .newDeployResourceCommand()
        .addProcessModel(process, "sequence.bpmn")
        .tenantId(TenantOwned.DEFAULT_TENANT_IDENTIFIER)
        .send()
        .join();
  }
}
