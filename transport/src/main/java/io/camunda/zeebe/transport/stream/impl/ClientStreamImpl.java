/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.transport.stream.impl;

import io.atomix.cluster.MemberId;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.scheduler.future.CompletableActorFuture;
import io.camunda.zeebe.transport.stream.api.ClientStream;
import io.camunda.zeebe.transport.stream.api.ClientStreamConsumer;
import io.camunda.zeebe.util.buffer.BufferWriter;
import java.util.Objects;
import java.util.Set;
import org.agrona.DirectBuffer;

/** Represents a registered client stream. * */
final class ClientStreamImpl<M extends BufferWriter> implements ClientStream<M> {
  private final ClientStreamIdImpl streamId;
  private final AggregatedClientStream<M> serverStream;
  private final DirectBuffer streamType;
  private final M metadata;
  private final ClientStreamConsumer clientStreamConsumer;

  private int capacity;

  ClientStreamImpl(
      final ClientStreamIdImpl streamId,
      final AggregatedClientStream<M> serverStream,
      final DirectBuffer streamType,
      final M metadata,
      final ClientStreamConsumer clientStreamConsumer,
      final int capacity) {
    this.streamId = streamId;
    this.serverStream = serverStream;
    this.streamType = streamType;
    this.metadata = metadata;
    this.clientStreamConsumer = clientStreamConsumer;
    this.capacity = capacity;
  }

  ActorFuture<Void> push(final DirectBuffer payload) {
    try {
      return clientStreamConsumer.push(payload);
    } catch (final Exception e) {
      return CompletableActorFuture.completedExceptionally(e);
    }
  }

  @Override
  public ClientStreamIdImpl streamId() {
    return streamId;
  }

  @Override
  public DirectBuffer streamType() {
    return streamType;
  }

  @Override
  public M metadata() {
    return metadata;
  }

  @Override
  public Set<MemberId> liveConnections() {
    return serverStream().liveConnections();
  }

  public AggregatedClientStream<M> serverStream() {
    return serverStream;
  }

  public ClientStreamConsumer clientStreamConsumer() {
    return clientStreamConsumer;
  }

  public int capacity() {
    return capacity;
  }

  public void capacity(final int capacity) {
    this.capacity = capacity;
  }

  @Override
  public int hashCode() {
    return Objects.hash(streamId, serverStream, streamType, metadata, clientStreamConsumer);
  }

  @Override
  public boolean equals(final Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj == null || obj.getClass() != getClass()) {
      return false;
    }

    //noinspection ConstantValue
    if (!(obj instanceof final ClientStreamImpl that)) {
      return false;
    }

    return Objects.equals(streamId, that.streamId)
        && Objects.equals(serverStream, that.serverStream)
        && Objects.equals(streamType, that.streamType)
        && Objects.equals(metadata, that.metadata)
        && Objects.equals(clientStreamConsumer, that.clientStreamConsumer);
  }

  @Override
  public String toString() {
    return "ClientStreamImpl["
        + "streamId="
        + streamId
        + ", "
        + "serverStream="
        + serverStream
        + ", "
        + "streamType="
        + streamType
        + ", "
        + "metadata="
        + metadata
        + ", "
        + "clientStreamConsumer="
        + clientStreamConsumer
        + ']';
  }
}
