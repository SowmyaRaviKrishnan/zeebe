/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.clustering.dynamic;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.management.cluster.PostOperationResponse;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.protocol.Protocol;
import io.camunda.zeebe.qa.util.actuator.ClusterActuator;
import io.camunda.zeebe.qa.util.cluster.TestCluster;
import io.camunda.zeebe.qa.util.topology.ClusterActuatorAssert;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;

final class Utils {

  static void scaleAndWait(final TestCluster cluster, final int newClusterSize) {
    final var response = scale(cluster, newClusterSize);

    // then - verify topology changes are completed
    Awaitility.await()
        .timeout(Duration.ofMinutes(2))
        .untilAsserted(() -> ClusterActuatorAssert.assertThat(cluster).hasAppliedChanges(response));
  }

  static PostOperationResponse scale(final TestCluster cluster, final int newClusterSize) {
    final var actuator = ClusterActuator.of(cluster.availableGateway());
    final var newBrokerSet = IntStream.range(0, newClusterSize).boxed().toList();

    // when
    final var response = actuator.scaleBrokers(newBrokerSet);
    assertChangeIsPlanned(response);
    return response;
  }

  static void assertChangeIsPlanned(final PostOperationResponse response) {
    assertThat(response.getPlannedChanges()).isNotEmpty();
    assertThat(response.getExpectedTopology())
        .usingRecursiveComparison()
        .ignoringFieldsOfTypes(OffsetDateTime.class)
        .isNotEqualTo(response.getCurrentTopology());
  }

  static void assertThatAllJobsCanBeCompleted(
      final List<Long> processInstanceKeys, final ZeebeClient zeebeClient, final String jobType) {
    final var jobs =
        zeebeClient
            .newActivateJobsCommand()
            .jobType(jobType)
            .maxJobsToActivate(2 * processInstanceKeys.size())
            .send()
            .join();

    Assertions.assertThat(jobs.getJobs().stream().map(ActivatedJob::getProcessInstanceKey).toList())
        .describedAs("Jobs from all partitions can be activated")
        .containsExactlyInAnyOrderElementsOf(processInstanceKeys);
    final var jobCompleteFutures =
        jobs.getJobs().stream()
            .map(job -> zeebeClient.newCompleteCommand(job).send().toCompletableFuture())
            .toList();
    Assertions.assertThat(
            CompletableFuture.allOf(jobCompleteFutures.toArray(CompletableFuture[]::new)))
        .describedAs("All jobs can be completed")
        .succeedsWithin(Duration.ofSeconds(2));
  }

  static List<Long> createInstanceWithAJobOnAllPartitions(
      final ZeebeClient zeebeClient, final String jobType, final int partitionsCount) {
    final var process =
        Bpmn.createExecutableProcess("processId")
            .startEvent()
            .serviceTask("task", t -> t.zeebeJobType(jobType))
            .endEvent()
            .done();
    zeebeClient.newDeployResourceCommand().addProcessModel(process, "process.bpmn").send().join();

    final List<Long> createdProcessInstances = new ArrayList<>();
    Awaitility.await("Process instances are created in all partitions")
        .ignoreExceptions() // Might throw exception when a partition has not yet received
        // deployment distribution
        .until(
            () -> {
              final var result =
                  zeebeClient
                      .newCreateInstanceCommand()
                      .bpmnProcessId("processId")
                      .latestVersion()
                      .send()
                      .join();
              createdProcessInstances.add(result.getProcessInstanceKey());
              // repeat until all partitions have atleast one process instance
              return createdProcessInstances.stream()
                      .map(Protocol::decodePartitionId)
                      .distinct()
                      .count()
                  == partitionsCount;
            });

    return createdProcessInstances;
  }
}
