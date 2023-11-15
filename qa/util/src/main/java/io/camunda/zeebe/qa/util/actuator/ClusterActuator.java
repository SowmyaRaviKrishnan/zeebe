/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.qa.util.actuator;

import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import feign.Body;
import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import io.camunda.zeebe.management.cluster.GetTopologyResponse;
import io.camunda.zeebe.management.cluster.PostOperationResponse;
import io.camunda.zeebe.qa.util.cluster.TestApplication;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.zeebe.containers.ZeebeNode;
import java.util.List;

public interface ClusterActuator {

  /**
   * Returns a {@link ClusterActuator} instance using the given node as upstream.
   *
   * @param node the node to connect to
   * @return a new instance of {@link ClusterActuator}
   */
  static ClusterActuator of(final ZeebeNode<?> node) {
    return ofAddress(node.getExternalMonitoringAddress());
  }

  /**
   * Returns a {@link ClusterActuator} instance using the given node as upstream.
   *
   * @param node the node to connect to
   * @return a new instance of {@link ClusterActuator}
   */
  static ClusterActuator of(final TestApplication<?> node) {
    return ofAddress(node.monitoringAddress());
  }

  /**
   * Returns a {@link ClusterActuator} instance using the given address as upstream.
   *
   * @param address the monitoring address
   * @return a new instance of {@link ClusterActuator}
   */
  static ClusterActuator ofAddress(final String address) {
    final var endpoint = String.format("http://%s/actuator/cluster", address);
    return of(endpoint);
  }

  /**
   * Returns a {@link ClusterActuator} instance using the given endpoint as upstream.
   *
   * @param endpoint the endpoint to connect to
   * @return a new instance of {@link ClusterActuator}
   */
  static ClusterActuator of(final String endpoint) {
    final var target = new HardCodedTarget<>(ClusterActuator.class, endpoint);
    return Feign.builder()
        .encoder(new JacksonEncoder(List.of(new Jdk8Module(), new JavaTimeModule())))
        .decoder(new JacksonDecoder(List.of(new Jdk8Module(), new JavaTimeModule())))
        .retryer(Retryer.NEVER_RETRY)
        .target(target);
  }

  /**
   * Request that the broker joins the partition with the given priority.
   *
   * @throws feign.FeignException if the request is not successful (e.g. 4xx or 5xx)
   */
  @RequestLine("POST /brokers/{brokerId}/partitions/{partitionId}")
  @Headers({"Content-Type: application/json", "Accept: application/json"})
  @Body("%7B\"priority\": \"{priority}\"%7D")
  PostOperationResponse joinPartition(
      @Param final int brokerId, @Param final int partitionId, @Param final int priority);

  /**
   * Request that the broker leaves the partition.
   *
   * @throws feign.FeignException if the request is not successful (e.g. 4xx or 5xx)
   */
  @RequestLine("DELETE /brokers/{brokerId}/partitions/{partitionId}")
  @Headers({"Content-Type: application/json", "Accept: application/json"})
  PostOperationResponse leavePartition(@Param final int brokerId, @Param final int partitionId);

  /**
   * Queries the current cluster topology
   *
   * @throws feign.FeignException if the request is not successful (e.g. 4xx or 5xx)
   */
  @RequestLine("GET")
  @Headers({"Content-Type: application/json", "Accept: application/json"})
  GetTopologyResponse getTopology();

  /**
   * Scales the given brokers up or down and reassigns partitions to the new brokers.
   *
   * @param ids
   * @throws feign.FeignException if the request is not successful (e.g. 4xx or 5xx)
   */
  @RequestLine("POST /brokers")
  @Headers({"Content-Type: application/json", "Accept: application/json"})
  PostOperationResponse scaleBrokers(@RequestBody List<Integer> ids);

  /**
   * Scales the given brokers up or down and reassigns partitions to the new brokers.
   *
   * @param dryRun if true, changes are not applied but only simulated.
   * @throws feign.FeignException if the request is not successful (e.g. 4xx or 5xx)
   */
  @RequestLine("POST /brokers?dryRun={dryRun}")
  @Headers({"Content-Type: application/json", "Accept: application/json"})
  PostOperationResponse scaleBrokers(@RequestBody List<Integer> ids, @Param boolean dryRun);

  /**
   * Request that the broker is added to the cluster.
   *
   * @throws feign.FeignException if the request is not successful (e.g. 4xx or 5xx)
   */
  @RequestLine("POST /brokers/{brokerId}")
  @Headers({"Content-Type: application/json", "Accept: application/json"})
  PostOperationResponse addBroker(@Param final int brokerId);

  /**
   * Request that the broker is removed from the cluster
   *
   * @throws feign.FeignException if the request is not successful (e.g. 4xx or 5xx)
   */
  @RequestLine("DELETE /brokers/{brokerId}")
  @Headers({"Content-Type: application/json", "Accept: application/json"})
  PostOperationResponse removeBroker(@Param final int brokerId);
}
