package org.camunda.tngp.util.actor;

import static org.camunda.tngp.util.EnsureUtil.*;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

public class ActorSchedulerImpl implements ActorScheduler
{
    private final ExecutorService exeutorService;
    private final Thread schedulerThread;

    private final ActorRunner[] runners;
    private final ActorSchedulerRunnable schedulerRunnable;

    public ActorSchedulerImpl(int threadCount, Supplier<ActorRunner> runnerFactory, Function<ActorRunner[], ActorSchedulerRunnable> schedulerFactory)
    {
        runners = createTaskRunners(threadCount, runnerFactory);
        schedulerRunnable = schedulerFactory.apply(runners);

        exeutorService = Executors.newFixedThreadPool(threadCount);
        for (int r = 0; r < runners.length; r++)
        {
            exeutorService.execute(runners[r]);
        }

        schedulerThread = new Thread(schedulerRunnable);
        schedulerThread.start();
    }

    private static ActorRunner[] createTaskRunners(int runnerCount, Supplier<ActorRunner> factory)
    {
        final ActorRunner[] runners = new ActorRunner[runnerCount];

        for (int i = 0; i < runnerCount; i++)
        {
            runners[i] = factory.get();
        }
        return runners;
    }

    @Override
    public ActorReference schedule(Actor actor)
    {
        return schedulerRunnable.schedule(actor);
    }

    @Override
    public void close()
    {
        exeutorService.shutdown();

        schedulerRunnable.close();

        for (int r = 0; r < runners.length; r++)
        {
            final ActorRunner runner = runners[r];
            runner.close();
        }

        try
        {
            schedulerThread.join(1000);
        }
        catch (Exception e)
        {
            System.err.println("Actor Scheduler did not exit within 1 second");
        }

        try
        {
            exeutorService.awaitTermination(10, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            System.err.println("Actor Runners did not exit within 10 seconds");
        }
    }

    @Override
    public String toString()
    {
        return schedulerRunnable.toString();
    }

    public static ActorScheduler createDefaultScheduler()
    {
        return newBuilder().build();
    }

    public static ActorScheduler createDefaultScheduler(int threadCount)
    {
        return newBuilder().threadCount(threadCount).build();
    }

    public static Builder newBuilder()
    {
        return new Builder();
    }

    public static final class Builder
    {
        private int threadCount = 1;
        private int baseIterationsPerActor = 1;
        private IdleStrategy runnerIdleStrategy = new BackoffIdleStrategy(100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MILLISECONDS.toNanos(1));
        private ErrorHandler runnerErrorHandler = Throwable::printStackTrace;

        private double imbalanceRunnerThreshold = 0.25;
        private Duration schedulerInitialBackoff = Duration.ofSeconds(1);
        private Duration schedulerMaxBackoff = Duration.ofSeconds(5);

        private Duration durationSamplePeriod = Duration.ofMillis(1);
        private int durationSampleCount = 128;

        public Builder threadCount(int threadCount)
        {
            this.threadCount = threadCount;
            return this;
        }

        public Builder baseIterationsPerActor(int baseIterationsPerActor)
        {
            this.baseIterationsPerActor = baseIterationsPerActor;
            return this;
        }

        public Builder runnerIdleStrategy(IdleStrategy idleStrategy)
        {
            this.runnerIdleStrategy = idleStrategy;
            return this;
        }

        public Builder runnerErrorHander(ErrorHandler errorHandler)
        {
            this.runnerErrorHandler = errorHandler;
            return this;
        }

        public Builder imbalanceThreshold(double imbalanceThreshold)
        {
            this.imbalanceRunnerThreshold = imbalanceThreshold;
            return this;
        }

        public Builder schedulerInitialBackoff(Duration initialBackoff)
        {
            this.schedulerInitialBackoff = initialBackoff;
            return this;
        }

        public Builder schedulerMaxBackoff(Duration maxBackoff)
        {
            this.schedulerMaxBackoff = maxBackoff;
            return this;
        }

        public Builder durationSamplePeriod(Duration samplePeriod)
        {
            this.durationSamplePeriod = samplePeriod;
            return this;
        }

        public Builder durationSampleCount(int sampleCount)
        {
            this.durationSampleCount = sampleCount;
            return this;
        }

        public ActorScheduler build()
        {
            ensureGreaterThan("thread count", threadCount, 0);
            ensureGreaterThan("base iterations per actor", baseIterationsPerActor, 0);
            ensureNotNull("runner idle strategy", runnerIdleStrategy);
            ensureNotNull("runner error handler", runnerErrorHandler);
            ensureNotNullOrGreaterThan("duration sample period", durationSamplePeriod, Duration.ofNanos(0));
            ensureGreaterThan("duration sample count", durationSampleCount, 0);
            ensureLessThanOrEqual("imbalance threshold", imbalanceRunnerThreshold, 1.0);
            ensureGreaterThanOrEqual("imbalance threshold", imbalanceRunnerThreshold, 0.0);
            ensureNotNullOrGreaterThan("scheduler initial backoff", schedulerInitialBackoff, Duration.ofNanos(0));
            ensureNotNullOrGreaterThan("scheduler max backoff", schedulerMaxBackoff, schedulerInitialBackoff);

            final Supplier<ActorRunner> runnerFactory = () -> new ActorRunner(baseIterationsPerActor, runnerIdleStrategy, runnerErrorHandler, durationSamplePeriod);
            final Function<Actor, ActorReferenceImpl> actorRefFactory = task -> new ActorReferenceImpl(task, durationSampleCount);
            final Function<ActorRunner[], ActorSchedulerRunnable> schedulerFactory = runners -> new ActorSchedulerRunnable(runners, actorRefFactory, imbalanceRunnerThreshold, schedulerInitialBackoff, schedulerMaxBackoff);

            return new ActorSchedulerImpl(threadCount, runnerFactory, schedulerFactory);
        }
    }

}
