/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.gateway.metrics.LongPollingMetrics;
import io.camunda.zeebe.qa.util.actuator.JobStreamActuator;
import io.camunda.zeebe.qa.util.cluster.TestStandaloneBroker;
import io.camunda.zeebe.qa.util.jobstream.JobStreamActuatorAssert;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration.TestZeebe;
import io.camunda.zeebe.test.util.junit.AutoCloseResources;
import io.camunda.zeebe.test.util.junit.AutoCloseResources.AutoCloseResource;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@AutoCloseResources
@ZeebeIntegration
final class ClientCancelPendingCommandTest {
  @TestZeebe private static final TestStandaloneBroker ZEEBE = new TestStandaloneBroker();

  @AutoCloseResource private final ZeebeClient client = ZEEBE.newClientBuilder().build();

  @Test
  void shouldCancelCommandOnFutureCancellation() {
    // given
    final var future =
        client
            .newActivateJobsCommand()
            .jobType("type")
            .maxJobsToActivate(10)
            .requestTimeout(Duration.ofHours(1))
            .send();
    final var metrics = new LongPollingMetrics();
    Awaitility.await("until we have one polling client")
        .untilAsserted(() -> assertThat(metrics.getBlockedRequestsCount("type")).isOne());

    // when - create some jobs after cancellation; the notification will trigger long polling to
    // remove cancelled requests. unfortunately we can't tell when cancellation is finished
    future.cancel(true);

    // then
    Awaitility.await("until no long polling clients are waiting")
        .untilAsserted(() -> assertThat(metrics.getBlockedRequestsCount("type")).isZero());
  }

  @Test
  void shouldRemoveStreamOnCancel() {
    // given
    final var uniqueWorkerName = UUID.randomUUID().toString();
    final var stream =
        client
            .newStreamJobsCommand()
            .jobType("jobs")
            .consumer(ignored -> {})
            .workerName(uniqueWorkerName)
            .open(error -> {});

    // when
    awaitStreamRegistered(uniqueWorkerName);
    stream.close();

    // then
    awaitStreamRemoved(uniqueWorkerName);
  }

  private void awaitStreamRegistered(final String workerName) {
    final var actuator = JobStreamActuator.of(ZEEBE);
    Awaitility.await("until a stream with the worker name '%s' is registered".formatted(workerName))
        .untilAsserted(
            () ->
                JobStreamActuatorAssert.assertThat(actuator)
                    .remoteStreams()
                    .haveWorker(1, workerName));
  }

  private void awaitStreamRemoved(final String workerName) {
    final var actuator = JobStreamActuator.of(ZEEBE);
    Awaitility.await("until no stream with worker name '%s' is registered".formatted(workerName))
        .untilAsserted(
            () ->
                JobStreamActuatorAssert.assertThat(actuator)
                    .remoteStreams()
                    .doNotHaveWorker(workerName));
  }
}
