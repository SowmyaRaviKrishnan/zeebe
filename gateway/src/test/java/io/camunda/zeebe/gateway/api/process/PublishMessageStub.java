/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.gateway.api.process;

import io.camunda.zeebe.gateway.api.util.StubbedBrokerClient;
import io.camunda.zeebe.gateway.api.util.StubbedBrokerClient.RequestStub;
import io.camunda.zeebe.gateway.impl.broker.request.BrokerPublishMessageRequest;
import io.camunda.zeebe.gateway.impl.broker.response.BrokerResponse;
import io.camunda.zeebe.protocol.impl.record.value.message.MessageRecord;

public final class PublishMessageStub
    implements RequestStub<BrokerPublishMessageRequest, BrokerResponse<MessageRecord>> {

  @Override
  public void registerWith(final StubbedBrokerClient gateway) {
    gateway.registerHandler(BrokerPublishMessageRequest.class, this);
  }

  @Override
  public BrokerResponse<MessageRecord> handle(final BrokerPublishMessageRequest request)
      throws Exception {
    final var requestRecord = request.getRequestWriter();
    final var responseRecord =
        new MessageRecord()
            .setName(requestRecord.getNameBuffer())
            .setCorrelationKey(requestRecord.getCorrelationKeyBuffer())
            .setTimeToLive(requestRecord.getTimeToLive())
            .setDeadline(requestRecord.getDeadline())
            .setVariables(requestRecord.getVariablesBuffer())
            .setMessageId(requestRecord.getMessageIdBuffer())
            .setTenantId(requestRecord.getTenantId());

    return new BrokerResponse<>(responseRecord, 0, 123L);
  }
}
