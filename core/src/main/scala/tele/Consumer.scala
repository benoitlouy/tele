package tele

// import scala.concurrent.duration._

import cats.effect._
import cats.syntax.all._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.kinesis.common.ConfigsBuilder
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.processor.ShardRecordProcessorFactory
import software.amazon.kinesis.coordinator.Scheduler
import fs2.concurrent.SignallingRef
import software.amazon.kinesis.retrieval.polling.PollingConfig
import tele.internal.{ RecordProcessor, WorkerStartedListener }
import software.amazon.kinesis.common.InitialPositionInStreamExtended
import software.amazon.kinesis.common.InitialPositionInStream

trait Consumer[F[_], A] { self =>
  def subscribe: Resource[F, fs2.Stream[F, A]]
  def map[B](f: A => B): Consumer[F, B] = new Consumer[F, B] {
    def subscribe: Resource[F, fs2.Stream[F, B]] = self.subscribe.map(_.map(f))
  }
}

object Consumer {

  def make[F[_]: Async: std.Console](
      appId: String,
      workerId: String,
      stream: String,
      kinesisClient: KinesisAsyncClient,
      ddbClient: DynamoDbAsyncClient,
      cwClient: CloudWatchAsyncClient
    ) = {
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
  }

  private def makeScheduler[F[_]: Async: std.Console](
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
              .flatTap(_ => std.Console[F].println("setting stopFlag to true"))
              .flatTap(_ => stopFlag.set(true))
          )
          .flatTap(_ => std.Console[F].println("check if worker has started"))
          .flatTap(_ => startFlag.get)
          .flatTap(_ => std.Console[F].println("worker started"))
        // .flatTap(_ => Async[F].sleep(2.seconds))
        // .flatTap(_ => std.Console[F].println("done sleeping"))
      )(fiber =>
        std
          .Console[F]
          .println("shutting down")
          .flatTap(_ => FutureLift[F].lift(scheduler.startGracefulShutdown()))
          // .flatTap(_ => Async[F].blocking(scheduler.shutdown()))
          .flatTap(_ => std.Console[F].println("signal sent"))
          .flatTap(_ => fiber.join)
          .flatTap(_ => std.Console[F].println("stopped"))
          .void
      )
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
