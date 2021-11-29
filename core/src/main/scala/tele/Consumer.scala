/*
 * Copyright 2021 Benoit Louy
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

package tele

import java.time.Instant
import java.util.Date

import scala.concurrent.duration._

import cats.Functor
import cats.effect._
import cats.syntax.all._
import fs2.concurrent.SignallingRef
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.checkpoint.CheckpointConfig
import software.amazon.kinesis.common.{ ConfigsBuilder, InitialPositionInStream, InitialPositionInStreamExtended }
import software.amazon.kinesis.coordinator.{ CoordinatorConfig, NoOpWorkerStateChangeListener, Scheduler }
import software.amazon.kinesis.leases.LeaseManagementConfig
import software.amazon.kinesis.lifecycle.LifecycleConfig
import software.amazon.kinesis.metrics.MetricsConfig
import software.amazon.kinesis.processor.{ ProcessorConfig, ShardRecordProcessorFactory }
import software.amazon.kinesis.retrieval.RetrievalConfig
import software.amazon.kinesis.retrieval.polling.PollingConfig

import tele.internal.{ FutureLift, RecordProcessor, WorkerStartedListener }
import fs2.Chunk

trait Consumer[F[_], A] { self =>
  def subscribe: Resource[F, fs2.Stream[F, A]]
}

object Consumer {

  def make[F[_]: Async](
      capacity: Int,
      appId: String,
      workerId: String,
      stream: String,
      kinesisClient: KinesisAsyncClient,
      ddbClient: DynamoDbAsyncClient,
      cwClient: CloudWatchAsyncClient,
      opt: Options
    ): Consumer[F, CommitableRecord[F]] = make(
    capacity,
    defaultScheduler(
      appId,
      workerId,
      stream,
      kinesisClient,
      ddbClient,
      cwClient,
      opt
    )
  )

  def make[F[_]: Async](
      capacity: Int,
      checkpointConfig: CheckpointConfig,
      coordinatorConfig: CoordinatorConfig,
      leaseManagementConfig: LeaseManagementConfig,
      lifecycleConfig: LifecycleConfig,
      metricsConfig: MetricsConfig,
      retrievalConfig: RetrievalConfig
    ): Consumer[F, CommitableRecord[F]] = make(
    capacity,
    customScheduler(
      checkpointConfig,
      coordinatorConfig,
      leaseManagementConfig,
      lifecycleConfig,
      metricsConfig,
      retrievalConfig
    )
  )

  private def make[F[_]: Async](
      capacity: Int,
      schedulerFactory: (Deferred[F, Unit], std.Dispatcher[F], ShardRecordProcessorFactory) => Scheduler
    ): Consumer[F, CommitableRecord[F]] =
    new Consumer[F, CommitableRecord[F]] {
      override def subscribe: Resource[F, fs2.Stream[F, CommitableRecord[F]]] =
        for {
          dispatcher <- std.Dispatcher[F]
          queue <- Resource.eval(std.Queue.bounded[F, Chunk[CommitableRecord[F]]](capacity))
          startFlag <- Resource.eval(Deferred[F, Unit])
          stopFlag <- Resource.eval(SignallingRef[F, Boolean](false))
          scheduler = schedulerFactory(
            startFlag,
            dispatcher,
            () => new RecordProcessor[F](records => dispatcher.unsafeRunSync(queue.offer(records)), dispatcher)
          )
          _ <- startScheduler(scheduler, startFlag, stopFlag)
        } yield fs2.Stream
          .fromQueueUnterminatedChunk(queue)
          .interruptWhen(stopFlag)
    }

  private def customScheduler[F[_]](
      checkpointConfig: CheckpointConfig,
      coordinatorConfig: CoordinatorConfig,
      leaseManagementConfig: LeaseManagementConfig,
      lifecycleConfig: LifecycleConfig,
      metricsConfig: MetricsConfig,
      retrievalConfig: RetrievalConfig
    ): (Deferred[F, Unit], std.Dispatcher[F], ShardRecordProcessorFactory) => Scheduler =
    (startFlag, dispatcher, factory) =>
      new Scheduler(
        checkpointConfig,
        coordinatorConfig.workerStateChangeListener(
          new WorkerStartedListener(startFlag, dispatcher, coordinatorConfig.workerStateChangeListener())
        ),
        leaseManagementConfig,
        lifecycleConfig,
        metricsConfig,
        new ProcessorConfig(factory),
        retrievalConfig
      )

  private def defaultScheduler[F[_]](
      appId: String,
      workerId: String,
      stream: String,
      kinesisClient: KinesisAsyncClient,
      ddbClient: DynamoDbAsyncClient,
      cwClient: CloudWatchAsyncClient,
      opt: Options
    ): (Deferred[F, Unit], std.Dispatcher[F], ShardRecordProcessorFactory) => Scheduler = {
    (startFlag, dispatcher, factory) =>
      val configBuilder = new ConfigsBuilder(
        stream,
        appId,
        kinesisClient,
        ddbClient,
        cwClient,
        workerId,
        factory
      )
      val retrievalConfig = {
        val config = configBuilder.retrievalConfig()
        opt.retrievalMode match {
          case Options.FanOut => ()
          case Options.Polling => config.retrievalSpecificConfig(new PollingConfig(stream, kinesisClient))
        }
        val initialPosition = opt.initialPosition match {
          case Options.Latest => InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)
          case Options.TrimHorizon =>
            InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON)
          case Options.AtTimestamp(ts) => InitialPositionInStreamExtended.newInitialPositionAtTimestamp(Date.from(ts))
        }
        config.initialPositionInStreamExtended(initialPosition)
        config
      }

      new Scheduler(
        configBuilder.checkpointConfig(),
        configBuilder
          .coordinatorConfig()
          .parentShardPollIntervalMillis(opt.parentShardPollInterval.toMillis)
          .workerStateChangeListener(WorkerStartedListener(startFlag, dispatcher, new NoOpWorkerStateChangeListener)),
        configBuilder
          .leaseManagementConfig()
          .shardSyncIntervalMillis(opt.shardSyncInterval.toMillis),
        configBuilder.lifecycleConfig(),
        configBuilder.metricsConfig(),
        configBuilder.processorConfig(),
        retrievalConfig
      )
  }

  private def startScheduler[F[_]: Async](
      scheduler: Scheduler,
      startFlag: Deferred[F, Unit],
      stopFlag: SignallingRef[F, Boolean]
    ): Resource[F, Unit] = for {
    supervisor <- std.Supervisor[F]
    _ <- Resource.make(
      supervisor
        .supervise(
          Async[F]
            .interruptible(false)(scheduler.run())
            .flatTap(_ => stopFlag.set(true))
        )
        .flatTap(_ => startFlag.get)
    )(fiber => FutureLift[F].lift(scheduler.startGracefulShutdown()).flatTap(_ => fiber.join).void)
  } yield ()

  sealed trait Options {
    val retrievalMode: Options.RetrievalMode
    val initialPosition: Options.InitialPosition
    val parentShardPollInterval: FiniteDuration
    val shardSyncInterval: FiniteDuration

    def withRetrievalMode(retrievalMode: Options.RetrievalMode): Options
    def withInitialPosition(initialPosition: Options.InitialPosition): Options
    def withParentShardPollInterval(parentShardPollInterval: FiniteDuration): Options
    def withShardSyncInterval(shardSyncInterval: FiniteDuration): Options
  }

  object Options {
    sealed trait RetrievalMode extends Product with Serializable
    case object Polling extends RetrievalMode
    case object FanOut extends RetrievalMode

    sealed trait InitialPosition extends Product with Serializable
    case object TrimHorizon extends InitialPosition
    case object Latest extends InitialPosition
    final case class AtTimestamp(ts: Instant) extends InitialPosition

    private case class OptionsImpl(
        retrievalMode: RetrievalMode,
        initialPosition: InitialPosition,
        parentShardPollInterval: FiniteDuration,
        shardSyncInterval: FiniteDuration)
      extends Options {
      override def withRetrievalMode(retrievalMode: RetrievalMode): Options = copy(retrievalMode = retrievalMode)
      override def withInitialPosition(initialPosition: InitialPosition): Options =
        copy(initialPosition = initialPosition)
      override def withParentShardPollInterval(parentShardPollInterval: FiniteDuration): Options =
        copy(parentShardPollInterval = parentShardPollInterval)
      override def withShardSyncInterval(shardSyncInterval: FiniteDuration): Options =
        copy(shardSyncInterval = shardSyncInterval)
    }

    def apply(): Options =
      OptionsImpl(
        retrievalMode = FanOut,
        initialPosition = Latest,
        parentShardPollInterval = 10.seconds,
        shardSyncInterval = 60.seconds
      )
  }

  implicit def consumerFunctorInstance[F[_]]: Functor[Consumer[F, *]] = new Functor[Consumer[F, *]] {
    override def map[A, B](fa: Consumer[F, A])(f: A => B): Consumer[F, B] = new Consumer[F, B] {
      override def subscribe: Resource[F, fs2.Stream[F, B]] = fa.subscribe.map(_.map(f))
    }
  }

  implicit class CommitableRecordConsumerOps[F[_]](val consumer: Consumer[F, CommitableRecord[F]]) extends AnyVal {
    def withSchema[A: Schema]: Consumer[F, DeserializedRecord[F, A]] = consumer.map { commitableRecord =>
      val buf = commitableRecord.record.data()
      val pos = buf.position()
      val bytes = Array.ofDim[Byte](buf.remaining())
      buf.get(bytes)
      buf.position(pos)
      Schema[A].decode(bytes) match {
        case Right(a) => commitableRecord.withValue(a)
        case Left(err) => commitableRecord.withError(err)
      }
    }
  }
}
