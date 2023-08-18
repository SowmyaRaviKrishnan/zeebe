/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.smoke;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceResult;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.qa.util.cluster.TestStandaloneBroker;
import io.camunda.zeebe.qa.util.cluster.ZeebePort;
import io.camunda.zeebe.qa.util.cluster.junit.ManageTestNodes;
import io.camunda.zeebe.qa.util.cluster.junit.ManageTestNodes.TestNode;
import io.camunda.zeebe.test.util.Strings;
import io.camunda.zeebe.test.util.asserts.TopologyAssert;
import io.camunda.zeebe.test.util.junit.AutoCloseResources;
import io.camunda.zeebe.test.util.junit.AutoCloseResources.AutoCloseResource;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Objects;

@ManageTestNodes
@AutoCloseResources
final class StandaloneBrokerIT {

  @TestNode private static final TestStandaloneBroker BROKER = new TestStandaloneBroker();

  @AutoCloseResource private final ZeebeClient client = BROKER.newClientBuilder().build();

  /** A simple smoke test to ensure the broker starts and can perform basic functionality. */
  @SmokeTest
  void smokeTest() {
    // given
    final var processId = Strings.newRandomValidBpmnId();
    final var process = Bpmn.createExecutableProcess(processId).startEvent().endEvent().done();
    final var partitionActuatorSpec =
        new RequestSpecBuilder()
            .setPort(BROKER.mappedPort(ZeebePort.MONITORING))
            .setBasePath("/actuator/partitions")
            .build();

    // when
    final var result = executeProcessInstance(processId, process);
    takeSnapshot(partitionActuatorSpec);

    // then
    assertThat(result.getBpmnProcessId()).isEqualTo(processId);
    await("until there is a snapshot available")
        .until(() -> getLatestSnapshotId(partitionActuatorSpec), Objects::nonNull);
  }

  private ProcessInstanceResult executeProcessInstance(
      final String processId, final io.camunda.zeebe.model.bpmn.BpmnModelInstance process) {
    await("until topology is complete").untilAsserted(this::assertTopologyIsComplete);

    client.newDeployResourceCommand().addProcessModel(process, processId + ".bpmn").send().join();
    return client
        .newCreateInstanceCommand()
        .bpmnProcessId(processId)
        .latestVersion()
        .withResult()
        .send()
        .join();
  }

  private void takeSnapshot(final RequestSpecification partitionActuatorSpec) {
    given()
        .spec(partitionActuatorSpec)
        .contentType(ContentType.JSON)
        .when()
        .post("takeSnapshot")
        .then()
        .statusCode(200);
  }

  private String getLatestSnapshotId(final RequestSpecification partitionActuatorSpec) {
    return given()
        .spec(partitionActuatorSpec)
        .when()
        .get()
        .then()
        .assertThat()
        .statusCode(200)
        .extract()
        .path("1.snapshotId");
  }

  private void assertTopologyIsComplete() {
    final var topology = client.newTopologyRequest().send().join();
    TopologyAssert.assertThat(topology).isComplete(1, 1, 1);
  }
}
