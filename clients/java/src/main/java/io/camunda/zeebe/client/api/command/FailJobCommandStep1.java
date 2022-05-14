/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.camunda.zeebe.client.api.command;

import io.camunda.zeebe.client.api.response.FailJobResponse;
import java.time.Duration;

public interface FailJobCommandStep1 {

  /**
   * Set the remaining retries of this job.
   *
   * <p>If the retries are greater than zero then this job will be picked up again by a job
   * subscription. Otherwise, an incident is created for this job.
   *
   * @param remainingRetries the remaining retries of this job (e.g. "jobEvent.getRetries() - 1")
   * @return the builder for this command. Call {@link #send()} to complete the command and send it
   *     to the broker.
   */
  FailJobCommandStep2 retries(int remainingRetries);

  interface FailJobCommandStep2 extends FinalCommandStep<FailJobResponse> {
    // the place for new optional parameters

    /**
     * Set the backoff timeout for failing this job.
     *
     * <p>If the backoff timeout is greater than zero and retries are greater than zero then after
     * the job this job will be picked up again after this backoff timeout will pass.
     *
     * @param backoffTimeout the backoff timeout of this job
     * @return the builder for this command. Call {@link #send()} to complete the command and send *
     *     it to the broker.
     */
    FailJobCommandStep2 retryBackoff(final Duration backoffTimeout);

    /**
     * Provide an error message describing the reason for the job failure. If failing the job
     * creates an incident, this error message will be used as incident message.
     *
     * @param errorMsg error message to be attached to the failed job
     * @return the builder for this command. Call {@link #send()} to complete the command and send
     *     it to the broker.
     */
    FailJobCommandStep2 errorMessage(String errorMsg);
  }
}
