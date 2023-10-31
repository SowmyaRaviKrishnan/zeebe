/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.bpmn.behavior;

import static io.camunda.zeebe.util.EnsureUtil.ensureNotNullOrEmpty;

import io.camunda.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.camunda.zeebe.engine.processing.common.EventHandle;
import io.camunda.zeebe.engine.processing.common.EventTriggerBehavior;
import io.camunda.zeebe.engine.processing.common.Failure;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableActivity;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableBoundaryEvent;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableCatchEvent;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.camunda.zeebe.engine.state.analyzers.CatchEventAnalyzer;
import io.camunda.zeebe.engine.state.analyzers.CatchEventAnalyzer.CatchEventTuple;
import io.camunda.zeebe.engine.state.immutable.ElementInstanceState;
import io.camunda.zeebe.engine.state.immutable.ProcessState;
import io.camunda.zeebe.engine.state.immutable.ProcessingState;
import io.camunda.zeebe.engine.state.instance.ElementInstance;
import io.camunda.zeebe.protocol.impl.record.value.escalation.EscalationRecord;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.camunda.zeebe.protocol.record.intent.EscalationIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.BpmnEventType;
import io.camunda.zeebe.stream.api.state.KeyGenerator;
import io.camunda.zeebe.util.Either;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.util.Optional;
import org.agrona.DirectBuffer;

public final class BpmnEventPublicationBehavior {

  private final ElementInstanceState elementInstanceState;
  private final EventHandle eventHandle;
  private final CatchEventAnalyzer catchEventAnalyzer;
  private final StateWriter stateWriter;
  private final KeyGenerator keyGenerator;

  private final ProcessState processState;

  private final TypedCommandWriter commandWriter;

  public BpmnEventPublicationBehavior(
      final ProcessingState processingState,
      final KeyGenerator keyGenerator,
      final EventTriggerBehavior eventTriggerBehavior,
      final BpmnStateBehavior stateBehavior,
      final Writers writers) {
    elementInstanceState = processingState.getElementInstanceState();
    eventHandle =
        new EventHandle(
            keyGenerator,
            processingState.getEventScopeInstanceState(),
            writers,
            processingState.getProcessState(),
            eventTriggerBehavior,
            stateBehavior);
    catchEventAnalyzer =
        new CatchEventAnalyzer(processingState.getProcessState(), elementInstanceState);
    stateWriter = writers.state();
    this.keyGenerator = keyGenerator;
    processState = processingState.getProcessState();
    commandWriter = writers.command();
  }

  /**
   * Throws an error event to the given element instance/catch event pair. Only throws the event if
   * the given element instance is exists and is accepting events, e.g. isn't terminating, wasn't
   * interrupted, etc.
   *
   * @param catchEventTuple a tuple representing a catch event and its current instance
   */
  public void throwErrorEvent(final CatchEventAnalyzer.CatchEventTuple catchEventTuple) {
    final ElementInstance eventScopeInstance = catchEventTuple.getElementInstance();
    final ExecutableCatchEvent catchEvent = catchEventTuple.getCatchEvent();

    if (eventHandle.canTriggerElement(eventScopeInstance, catchEvent.getId())) {
      eventHandle.activateElement(
          catchEvent, eventScopeInstance.getKey(), eventScopeInstance.getValue());
    }
  }

  /**
   * Throws an error event to the given element instance/catch event pair. Only throws the event if
   * the given element instance is exists and is accepting events, e.g. isn't terminating, wasn't
   * interrupted, etc.
   *
   * @param catchEventTuple a tuple representing a catch event and its current instance
   */
  public void throwErrorEvent(
      final CatchEventAnalyzer.CatchEventTuple catchEventTuple, final DirectBuffer variables) {
    final ElementInstance eventScopeInstance = catchEventTuple.getElementInstance();
    final ExecutableCatchEvent catchEvent = catchEventTuple.getCatchEvent();

    if (eventHandle.canTriggerElement(eventScopeInstance, catchEvent.getId())) {
      eventHandle.activateElement(
          catchEvent, eventScopeInstance.getKey(), eventScopeInstance.getValue(), variables);
    }
  }

  /**
   * Finds the right catch event for the given error. This is done by going up through the scope
   * hierarchy recursively until a matching catch event is found. If none are found, a failure is
   * returned.
   *
   * <p>The returned {@link CatchEventTuple} can be used to throw the event via {@link
   * #throwErrorEvent(CatchEventTuple)}.
   *
   * @param errorCode the error code of the error event
   * @param context the current element context
   * @return a valid {@link CatchEventTuple} if a catch event is found, or a failure otherwise
   */
  public Either<Failure, CatchEventTuple> findErrorCatchEvent(
      final DirectBuffer errorCode, final BpmnElementContext context) {
    final var flowScopeInstance = elementInstanceState.getInstance(context.getFlowScopeKey());
    return catchEventAnalyzer.findErrorCatchEvent(errorCode, flowScopeInstance, Optional.empty());
  }

  /**
   * Finds the right catch event for the given escalation. This is done by going up through the
   * scope hierarchy recursively until a matching catch event is found. Otherwise, it returns {@link
   * Optional#empty()}.
   *
   * @param escalationCode the escalation code of the escalation event
   * @param context the current element context
   * @return a valid {@link CatchEventTuple} if a catch event is found, Otherwise, it returns {@link
   *     Optional#empty()}
   */
  public Optional<CatchEventTuple> findEscalationCatchEvent(
      final DirectBuffer escalationCode, final BpmnElementContext context) {
    final var flowScopeInstance = elementInstanceState.getInstance(context.getFlowScopeKey());
    return catchEventAnalyzer.findEscalationCatchEvent(escalationCode, flowScopeInstance);
  }

  /**
   * Throws an escalation event to the given element instance/catch event pair. Only throws the
   * event if the given element instance is exists and is accepting events, e.g. isn't terminating,
   * wasn't interrupted, etc.
   *
   * @param throwElementId the element id of the escalation throw event
   * @param escalationCode the escalation code of escalation
   * @param context process instance-related data of the element that is executed
   * @return returns true if the escalation throw event can be completed, false otherwise
   */
  public boolean throwEscalationEvent(
      final DirectBuffer throwElementId,
      final DirectBuffer escalationCode,
      final BpmnElementContext context) {

    ensureNotNullOrEmpty("escalationCode", escalationCode);

    final var record = new EscalationRecord();
    record.setThrowElementId(throwElementId);
    record.setEscalationCode(BufferUtil.bufferAsString(escalationCode));
    record.setProcessInstanceKey(context.getProcessInstanceKey());

    final var escalationCatchEvent = findEscalationCatchEvent(escalationCode, context);

    boolean canBeCompleted = true;
    boolean escalated = false;
    final var key = keyGenerator.nextKey();

    if (escalationCatchEvent.isPresent()) {
      final var catchEventTuple = escalationCatchEvent.get();
      final var eventScopeInstance = catchEventTuple.getElementInstance();
      final ExecutableCatchEvent catchEvent = catchEventTuple.getCatchEvent();
      // update catch element id
      record.setCatchElementId(catchEvent.getId());

      // if the escalation catch event is interrupt event, then throw event is not allowed to
      // complete.
      canBeCompleted = !catchEvent.isInterrupting();

      if (eventHandle.canTriggerElement(eventScopeInstance, catchEvent.getId())) {
        eventHandle.activateElement(
            catchEvent, eventScopeInstance.getKey(), eventScopeInstance.getValue());
        stateWriter.appendFollowUpEvent(key, EscalationIntent.ESCALATED, record);
        escalated = true;
      }
    }

    if (!escalated) {
      stateWriter.appendFollowUpEvent(key, EscalationIntent.NOT_ESCALATED, record);
    }

    return canBeCompleted;
  }

  public void activateCompensationHandler(
      final String elementId, final BpmnElementContext context) {

    // find the boundary event
    final var activityToCompensate =
        processState.getFlowElement(
            context.getProcessDefinitionKey(),
            context.getTenantId(),
            BufferUtil.wrapString(elementId),
            ExecutableActivity.class);

    final ExecutableBoundaryEvent boundaryEvent =
        activityToCompensate.getBoundaryEvents().stream()
            .filter(b -> b.getEventType() == BpmnEventType.COMPENSATION)
            .findFirst()
            .orElseThrow();

    final long boundaryEventKey = keyGenerator.nextKey();

    final ProcessInstanceRecord boundaryEventRecord = new ProcessInstanceRecord();
    boundaryEventRecord.wrap(context.getRecordValue());
    boundaryEventRecord.setBpmnElementType(BpmnElementType.BOUNDARY_EVENT);
    boundaryEventRecord.setElementId(boundaryEvent.getId());

    stateWriter.appendFollowUpEvent(
        boundaryEventKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, boundaryEventRecord);
    stateWriter.appendFollowUpEvent(
        boundaryEventKey, ProcessInstanceIntent.ELEMENT_ACTIVATED, boundaryEventRecord);
    stateWriter.appendFollowUpEvent(
        boundaryEventKey, ProcessInstanceIntent.ELEMENT_COMPLETING, boundaryEventRecord);
    stateWriter.appendFollowUpEvent(
        boundaryEventKey, ProcessInstanceIntent.ELEMENT_COMPLETED, boundaryEventRecord);

    // activate the compensation handler

    final ExecutableActivity compensationHandler =
        boundaryEvent.getCompensation().getCompensationHandler();
    final DirectBuffer compensationHandlerId = compensationHandler.getId();

    final ProcessInstanceRecord compensationHandlerRecord = new ProcessInstanceRecord();
    compensationHandlerRecord.wrap(context.getRecordValue());
    compensationHandlerRecord.setBpmnElementType(compensationHandler.getElementType());
    compensationHandlerRecord.setElementId(compensationHandlerId);

    commandWriter.appendNewCommand(
        ProcessInstanceIntent.ACTIVATE_ELEMENT, compensationHandlerRecord);
  }
}
