/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.logstreams.impl.log;

import static io.camunda.zeebe.dispatcher.impl.log.LogBufferAppender.RESULT_PADDING_AT_END_OF_PARTITION;
import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.headerLength;
import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.metadataOffset;
import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.setKey;
import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.setMetadataLength;
import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.setPosition;
import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.setSourceEventPosition;
import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.setTimestamp;
import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.valueOffset;

import io.camunda.zeebe.dispatcher.ClaimedFragment;
import io.camunda.zeebe.dispatcher.Dispatcher;
import io.camunda.zeebe.logstreams.log.LogStreamRecordWriter;
import io.camunda.zeebe.scheduler.clock.ActorClock;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.scheduler.future.CompletableActorFuture;
import io.camunda.zeebe.util.buffer.BufferWriter;
import io.camunda.zeebe.util.buffer.DirectBufferWriter;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

final class LogStreamWriterImpl implements LogStreamRecordWriter {
  private final DirectBufferWriter metadataWriterInstance = new DirectBufferWriter();
  private final DirectBufferWriter bufferWriterInstance = new DirectBufferWriter();
  private final ClaimedFragment claimedFragment = new ClaimedFragment();

  private final Dispatcher logWriteBuffer;
  private final int partitionId;
  private long key;
  private long sourceRecordPosition = -1L;
  private BufferWriter metadataWriter;
  private BufferWriter valueWriter;

  LogStreamWriterImpl(final int partitionId, final Dispatcher logWriteBuffer) {
    this.logWriteBuffer = logWriteBuffer;
    this.partitionId = partitionId;

    reset();
  }

  @Override
  public LogStreamRecordWriter keyNull() {
    return key(LogEntryDescriptor.KEY_NULL_VALUE);
  }

  @Override
  public LogStreamRecordWriter key(final long key) {
    this.key = key;
    return this;
  }

  @Override
  public LogStreamRecordWriter sourceRecordPosition(final long position) {
    sourceRecordPosition = position;
    return this;
  }

  @Override
  public LogStreamRecordWriter metadata(
      final DirectBuffer buffer, final int offset, final int length) {
    metadataWriterInstance.wrap(buffer, offset, length);
    return this;
  }

  @Override
  public LogStreamRecordWriter metadata(final DirectBuffer buffer) {
    return metadata(buffer, 0, buffer.capacity());
  }

  @Override
  public LogStreamRecordWriter metadataWriter(final BufferWriter writer) {
    metadataWriter = writer;
    return this;
  }

  @Override
  public LogStreamRecordWriter value(
      final DirectBuffer value, final int valueOffset, final int valueLength) {
    return valueWriter(bufferWriterInstance.wrap(value, valueOffset, valueLength));
  }

  @Override
  public LogStreamRecordWriter value(final DirectBuffer value) {
    return value(value, 0, value.capacity());
  }

  @Override
  public LogStreamRecordWriter valueWriter(final BufferWriter writer) {
    valueWriter = writer;
    return this;
  }

  @Override
  public void reset() {
    key = LogEntryDescriptor.KEY_NULL_VALUE;
    metadataWriter = metadataWriterInstance;
    valueWriter = null;
    sourceRecordPosition = -1L;

    bufferWriterInstance.reset();
    metadataWriterInstance.reset();
  }

  @Override
  public ActorFuture<Long> tryWrite() {
    if (valueWriter == null) {
      return CompletableActorFuture.completed(0L);
    }

    long result = -1;

    final int valueLength = valueWriter.getLength();
    final int metadataLength = metadataWriter.getLength();

    // claim fragment in log write buffer
    final long claimedPosition = claimLogEntry(valueLength, metadataLength);
    if (claimedPosition < 0) {
      return CompletableActorFuture.completedExceptionally(
          new IllegalStateException(
              "Failed to claim batch (result: %d)".formatted(claimedPosition)));
    }

    try {
      final MutableDirectBuffer writeBuffer = claimedFragment.getBuffer();
      final int bufferOffset = claimedFragment.getOffset();

      // write log entry header
      setPosition(writeBuffer, bufferOffset, claimedPosition);
      setSourceEventPosition(writeBuffer, bufferOffset, sourceRecordPosition);
      setKey(writeBuffer, bufferOffset, key);
      setTimestamp(writeBuffer, bufferOffset, ActorClock.currentTimeMillis());
      setMetadataLength(writeBuffer, bufferOffset, (short) metadataLength);

      if (metadataLength > 0) {
        metadataWriter.write(writeBuffer, metadataOffset(bufferOffset));
      }

      // write log entry
      valueWriter.write(writeBuffer, valueOffset(bufferOffset, metadataLength));

      result = claimedPosition;
      claimedFragment.commit();
    } catch (final Exception e) {
      claimedFragment.abort();
      return CompletableActorFuture.completedExceptionally(e);
    } finally {
      reset();
    }

    return CompletableActorFuture.completed(result);
  }

  private long claimLogEntry(final int valueLength, final int metadataLength) {
    final int framedLength = valueLength + headerLength(metadataLength);

    long claimedPosition;
    do {

      claimedPosition =
          logWriteBuffer.claimSingleFragment(claimedFragment, framedLength, partitionId);
    } while (claimedPosition == RESULT_PADDING_AT_END_OF_PARTITION);

    return claimedPosition;
  }
}
