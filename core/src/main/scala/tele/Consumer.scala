package tele

import cats.effect._
import cats.syntax.all._
import fs2.concurrent.SignallingRef
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ ConfigsBuilder, InitialPositionInStream, InitialPositionInStreamExtended }
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.processor.ShardRecordProcessorFactory
import software.amazon.kinesis.retrieval.polling.PollingConfig

import tele.internal.{ FutureLift, RecordProcessor, WorkerStartedListener }

trait Consumer[F[_], A] { self =>
  def subscribe: Resource[F, fs2.Stream[F, A]]
  def map[B](f: A => B): Consumer[F, B] = new Consumer[F, B] {
    def subscribe: Resource[F, fs2.Stream[F, B]] = self.subscribe.map(_.map(f))
  }
}

object Consumer {

  def make[F[_]: Async](
      appId: String,
      workerId: String,
      stream: String,
      kinesisClient: KinesisAsyncClient,
      ddbClient: DynamoDbAsyncClient,
      cwClient: CloudWatchAsyncClient
    ): Consumer[F, CommitableRecord[F]] =
    new Consumer[F, CommitableRecord[F]] {
      override def subscribe: Resource[F, fs2.Stream[F, CommitableRecord[F]]] = {
        for {
          dispatcher <- std.Dispatcher[F]
          queue <- Resource.eval(std.Queue.unbounded[F, List[CommitableRecord[F]]])
          startFlag <- Resource.eval(Deferred[F, Unit])
          stopFlag <- Resource.eval(SignallingRef[F, Boolean](false))
          _ <- makeScheduler(appId, workerId, stream, kinesisClient, ddbClient, cwClient, startFlag, stopFlag).apply(
            () => new RecordProcessor[F](records => dispatcher.unsafeRunSync(queue.offer(records)), dispatcher)
          )
        } yield fs2.Stream
          .fromQueueUnterminated(queue)
          .interruptWhen(stopFlag)
          .flatMap(fs2.Stream.emits)
      }
    }

  private def makeScheduler[F[_]: Async](
      appId: String,
      workerId: String,
      stream: String,
      kinesisClient: KinesisAsyncClient,
      ddbClient: DynamoDbAsyncClient,
      cwClient: CloudWatchAsyncClient,
      startFlag: Deferred[F, Unit],
      stopFlag: SignallingRef[F, Boolean]
    ): ShardRecordProcessorFactory => Resource[F, Scheduler] = factory =>
    for {
      configBuilder <- Resource.pure(
        new ConfigsBuilder(
          stream,
          appId,
          kinesisClient,
          ddbClient,
          cwClient,
          workerId,
          factory
        )
      )
      retrievalConfig = {
        val config = configBuilder.retrievalConfig()
        config.retrievalSpecificConfig(new PollingConfig(stream, kinesisClient))
        config.initialPositionInStreamExtended(
          InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON)
        )
        config
      }
      dispatcher <- std.Dispatcher[F]
      scheduler <- Resource.pure(
        new Scheduler(
          configBuilder.checkpointConfig(),
          configBuilder
            .coordinatorConfig()
            // .parentShardPollIntervalMillis(1000L)
            .workerStateChangeListener(WorkerStartedListener(startFlag, dispatcher)),
          configBuilder.leaseManagementConfig(), // .shardSyncIntervalMillis(1000L),
          configBuilder.lifecycleConfig(),
          configBuilder.metricsConfig(),
          configBuilder.processorConfig(),
          retrievalConfig
        )
      )
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
    } yield scheduler

  implicit class CommitableRecordConsumerOps[F[_]](val consumer: Consumer[F, CommitableRecord[F]]) extends AnyVal {
    def as[A: Schema]: Consumer[F, DeserializedRecord[F, A]] = consumer.map { commitableRecord =>
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
