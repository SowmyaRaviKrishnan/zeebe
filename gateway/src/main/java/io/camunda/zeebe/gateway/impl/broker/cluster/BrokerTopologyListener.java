/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.gateway.impl.broker.cluster;

import io.atomix.cluster.MemberId;

/** Listener which will be notified when a broker is added or removed from the topology. */
public interface BrokerTopologyListener {

  void brokerAdded(MemberId memberId);

  void brokerRemoved(MemberId memberId);
}
