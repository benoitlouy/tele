package tele.internal

import scala.jdk.CollectionConverters._

import cats.effect._
import cats.syntax.all._

import software.amazon.kinesis.lifecycle.events._
import software.amazon.kinesis.processor.ShardRecordProcessor
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber
import software.amazon.kinesis.retrieval.KinesisClientRecord

import tele.CommitableRecord
import software.amazon.kinesis.processor.RecordProcessorCheckpointer

private[tele] class RecordProcessor[F[_]: Async](
    callback: List[CommitableRecord[F]] => Unit,
    dispatcher: std.Dispatcher[F])
  extends ShardRecordProcessor {
  private[tele] var shardId: String = _
  private[tele] var extendedSequenceNumber: ExtendedSequenceNumber = _
  private val lastCheckpoint: std.Queue[F, Option[(String, Long)]] =
    dispatcher.unsafeRunSync(std.Queue.bounded[F, Option[(String, Long)]](1))

  override def initialize(init: InitializationInput): Unit = {
    shardId = init.shardId()
    extendedSequenceNumber = init.extendedSequenceNumber()
    dispatcher.unsafeRunSync(lastCheckpoint.offer(None))
  }

  private def commit(record: KinesisClientRecord, checkpointer: RecordProcessorCheckpointer): F[Unit] = for {
    last <- lastCheckpoint.take
    update <- {
      val newCheckpoint = (record.sequenceNumber(), record.subSequenceNumber())
      if (last.forall(_ < newCheckpoint))
        Sync[F]
          .blocking(checkpointer.checkpoint(record.sequenceNumber(), record.subSequenceNumber()))
          .as(Some(newCheckpoint))
      else Sync[F].pure(last)
    }
    _ <- lastCheckpoint.offer(update)
  } yield ()

  override def processRecords(processRecords: ProcessRecordsInput): Unit = {
    val checkpointer = processRecords.checkpointer()
    val commitableRecords = processRecords.records().asScala.toList.map { record =>
      CommitableRecord(record, commit(record, checkpointer))
    }
    callback(commitableRecords)
  }

  override def leaseLost(x: LeaseLostInput): Unit = ()

  override def shardEnded(shardEnded: ShardEndedInput): Unit = {
    shardEnded.checkpointer.checkpoint()
  }

  override def shutdownRequested(x: ShutdownRequestedInput): Unit = ()

}
