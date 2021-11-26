package tele

import scala.jdk.CollectionConverters._
import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._
import software.amazon.kinesis.processor.ShardRecordProcessor
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput
import software.amazon.kinesis.lifecycle.events.InitializationInput
import software.amazon.kinesis.lifecycle.events.LeaseLostInput
import software.amazon.kinesis.lifecycle.events.ShardEndedInput
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.kinesis.common.ConfigsBuilder
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.processor.ShardRecordProcessorFactory
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber
import software.amazon.kinesis.retrieval.KinesisClientRecord
import cats.effect.std.Dispatcher
// import cats.effect.std.Supervisor
import fs2.concurrent.SignallingRef
import software.amazon.kinesis.retrieval.polling.PollingConfig
import software.amazon.kinesis.coordinator.WorkerStateChangeListener

trait Consumer[F[_], A] {
  def subscribe: Resource[F, fs2.Stream[F, A]]
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
          dispatcher <- Dispatcher[F]
          queue <- Resource.eval(Queue.unbounded[F, List[CommitableRecord[F]]])
          startFlag <- Resource.eval(Deferred[F, Unit])
          stopFlag <- Resource.eval(SignallingRef[F, Boolean](false))
          _ <- makeScheduler(appId, workerId, stream, kinesisClient, ddbClient, cwClient, startFlag, stopFlag).apply(
            () => new RecordProcessor[F](records => dispatcher.unsafeRunSync(queue.offer(records)))
          )
        } yield fs2.Stream.fromQueueUnterminated(queue).flatMap(fs2.Stream.emits) // .interruptWhen()
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
        config
      }
      dispatcher <- Dispatcher[F]
      scheduler <- Resource.pure(
        new Scheduler(
          configBuilder.checkpointConfig(),
          configBuilder.coordinatorConfig().workerStateChangeListener(WorkerStartedListener(startFlag, dispatcher)),
          configBuilder.leaseManagementConfig(),
          configBuilder.lifecycleConfig(),
          configBuilder.metricsConfig(),
          configBuilder.processorConfig(),
          retrievalConfig
        )
      )
      supervisor <- std.Supervisor[F]
      _ <- Resource.make(
        supervisor
          .supervise(Async[F].blocking(scheduler.run()).flatTap(_ => stopFlag.set(true)))
          .flatTap(_ => std.Console[F].println("check if worker has started"))
          .flatTap(_ => startFlag.get)
          .flatTap(_ => std.Console[F].println("worker started"))
      )(fiber =>
        std
          .Console[F]
          .println("shutting down")
          .flatTap(_ => Async[F].blocking(scheduler.shutdown()))
          .flatTap(_ => std.Console[F].println("signal sent"))
          .flatTap(_ => fiber.join)
          .flatTap(_ => std.Console[F].println("stopped"))
          .void
      )
    } yield scheduler

  class RecordProcessor[F[_]: Sync](callback: List[CommitableRecord[F]] => Unit) extends ShardRecordProcessor {
    private[tele] var shardId: String = _
    private[tele] var extendedSequenceNumber: ExtendedSequenceNumber = _
    override def initialize(init: InitializationInput): Unit = {
      shardId = init.shardId()
      extendedSequenceNumber = init.extendedSequenceNumber()
    }

    override def processRecords(processRecords: ProcessRecordsInput): Unit = {
      println(s"AT SHARD END ${processRecords.isAtShardEnd()}")
      val commit: KinesisClientRecord => F[Unit] = record =>
        Sync[F].blocking(processRecords.checkpointer().checkpoint(record.sequenceNumber(), record.subSequenceNumber()))

      val commitableRecords = processRecords.records().asScala.toList.map { record =>
        CommitableRecord(record, commit)
      }
      callback(commitableRecords)
    }

    override def leaseLost(x: LeaseLostInput): Unit = {
      println("LEASE LOST")
    }

    override def shardEnded(shardEnded: ShardEndedInput): Unit = {
      println("SHARD ENDED")
      shardEnded.checkpointer.checkpoint()
    }

    override def shutdownRequested(x: ShutdownRequestedInput): Unit = {
      println("SHUTDOWN REQUESTED")
    }

  }

  final case class CommitableRecord[F[_]](record: KinesisClientRecord, commit: KinesisClientRecord => F[Unit])

  final case class WorkerStartedListener[F[_]](started: Deferred[F, Unit], dispatcher: Dispatcher[F])
    extends WorkerStateChangeListener {
    override def onWorkerStateChange(newState: WorkerStateChangeListener.WorkerState): Unit = {
      println(s"NEWSTATE $newState")
      if (newState == WorkerStateChangeListener.WorkerState.STARTED) {
        val _ = dispatcher.unsafeRunSync(started.complete(()))
      }
    }
    override def onAllInitializationAttemptsFailed(e: Throwable): Unit =
      throw e // scalafix:ok
  }
}
