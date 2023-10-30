/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.deployment.model.transformer;

import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableIntermediateThrowEvent;
import io.camunda.zeebe.engine.processing.deployment.model.transformation.ModelElementTransformer;
import io.camunda.zeebe.engine.processing.deployment.model.transformation.TransformContext;
import io.camunda.zeebe.model.bpmn.instance.Activity;
import io.camunda.zeebe.model.bpmn.instance.CompensateEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.EscalationEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.IntermediateThrowEvent;
import io.camunda.zeebe.model.bpmn.instance.LinkEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.MessageEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.SignalEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.camunda.zeebe.protocol.record.value.BpmnEventType;

public final class IntermediateThrowEventTransformer
    implements ModelElementTransformer<IntermediateThrowEvent> {

  private final JobWorkerElementTransformer<IntermediateThrowEvent> jobWorkerElementTransformer =
      new JobWorkerElementTransformer<>(IntermediateThrowEvent.class);

  @Override
  public Class<IntermediateThrowEvent> getType() {
    return IntermediateThrowEvent.class;
  }

  @Override
  public void transform(final IntermediateThrowEvent element, final TransformContext context) {
    final var process = context.getCurrentProcess();
    final var throwEvent =
        process.getElementById(element.getId(), ExecutableIntermediateThrowEvent.class);

    throwEvent.setEventType(BpmnEventType.NONE);

    if (isMessageEvent(element)) {
      throwEvent.setEventType(BpmnEventType.MESSAGE);
      if (hasTaskDefinition(element)) {
        jobWorkerElementTransformer.transform(element, context);
      }
    } else if (isLinkEvent(element)) {
      transformLinkEventDefinition(element, context, throwEvent);
    } else if (isEscalationEvent(element)) {
      transformEscalationEventDefinition(element, context);
    } else if (isSignalEvent(element)) {
      transformSignalEventDefinition(element, context);
    } else if (isCompensationEvent(element)) {
      transformCompensationEventDefinition(element, context);
    }
  }

  private void transformCompensationEventDefinition(
      final IntermediateThrowEvent element, final TransformContext context) {
    final var currentProcess = context.getCurrentProcess();
    final var executableElement =
        currentProcess.getElementById(element.getId(), ExecutableIntermediateThrowEvent.class);

    final var eventDefinition =
        (CompensateEventDefinition) element.getEventDefinitions().iterator().next();

    //    final Collection<Association> associations = element.getParentElement()
    //        .getChildElementsByType(Association.class);
    //
    //    associations.stream().filter(association -> association.getSource())

    final Activity compensationHandlerActivity = eventDefinition.getActivity();

    //    final ExecutableActivity compensationHandler =
    //        currentProcess.getElementById(
    //            compensationHandlerActivity.getId(), ExecutableActivity.class);
    //
    //    final ExecutableCompensation compensation = new
    // ExecutableCompensation(eventDefinition.getId());
    //    compensation.setCompensationHandler(compensationHandler);
    //    executableElement.setCompensation(compensation);

    executableElement.setEventType(BpmnEventType.COMPENSATION);
  }

  private boolean isMessageEvent(final IntermediateThrowEvent element) {
    return element.getEventDefinitions().stream()
        .anyMatch(MessageEventDefinition.class::isInstance);
  }

  private boolean isLinkEvent(final IntermediateThrowEvent element) {
    return element.getEventDefinitions().stream().anyMatch(LinkEventDefinition.class::isInstance);
  }

  private boolean isEscalationEvent(final IntermediateThrowEvent element) {
    return element.getEventDefinitions().stream()
        .anyMatch(EscalationEventDefinition.class::isInstance);
  }

  private boolean isSignalEvent(final IntermediateThrowEvent element) {
    return element.getEventDefinitions().stream().anyMatch(SignalEventDefinition.class::isInstance);
  }

  private boolean isCompensationEvent(final IntermediateThrowEvent element) {
    return element.getEventDefinitions().stream()
        .anyMatch(CompensateEventDefinition.class::isInstance);
  }

  private boolean hasTaskDefinition(final IntermediateThrowEvent element) {
    return element.getSingleExtensionElement(ZeebeTaskDefinition.class) != null;
  }

  private void transformLinkEventDefinition(
      final IntermediateThrowEvent element,
      final TransformContext context,
      final ExecutableIntermediateThrowEvent executableThrowEventElement) {

    final var eventDefinition =
        (LinkEventDefinition) element.getEventDefinitions().iterator().next();

    final var name = eventDefinition.getName();
    final var executableLink = context.getLink(name);
    executableThrowEventElement.setLink(executableLink);
    executableThrowEventElement.setEventType(BpmnEventType.LINK);
  }

  private void transformEscalationEventDefinition(
      final IntermediateThrowEvent element, final TransformContext context) {
    final var currentProcess = context.getCurrentProcess();
    final var executableElement =
        currentProcess.getElementById(element.getId(), ExecutableIntermediateThrowEvent.class);

    final var eventDefinition =
        (EscalationEventDefinition) element.getEventDefinitions().iterator().next();
    final var escalation = eventDefinition.getEscalation();
    final var executableEscalation = context.getEscalation(escalation.getId());
    executableElement.setEscalation(executableEscalation);
    executableElement.setEventType(BpmnEventType.ESCALATION);
  }

  private void transformSignalEventDefinition(
      final IntermediateThrowEvent element, final TransformContext context) {
    final var currentProcess = context.getCurrentProcess();
    final var executableElement =
        currentProcess.getElementById(element.getId(), ExecutableIntermediateThrowEvent.class);

    final var eventDefinition =
        (SignalEventDefinition) element.getEventDefinitions().iterator().next();
    final var signal = eventDefinition.getSignal();
    final var executableSignal = context.getSignal(signal.getId());
    executableElement.setSignal(executableSignal);
    executableElement.setEventType(BpmnEventType.SIGNAL);
  }
}
