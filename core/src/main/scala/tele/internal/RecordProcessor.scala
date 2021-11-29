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

package tele.internal

import scala.jdk.CollectionConverters._

import cats.effect._
import cats.syntax.all._
import software.amazon.kinesis.lifecycle.events._
import software.amazon.kinesis.processor.{ RecordProcessorCheckpointer, ShardRecordProcessor }
import software.amazon.kinesis.retrieval.KinesisClientRecord
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber

import tele.CommitableRecord
import fs2.Chunk

private[tele] class RecordProcessor[F[_]: Async](
    callback: Chunk[CommitableRecord[F]] => Unit,
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
    val commitableRecords = processRecords.records().asScala.map { record =>
      CommitableRecord(record, commit(record, checkpointer))
    }
    callback(Chunk.buffer(commitableRecords))
  }

  override def leaseLost(x: LeaseLostInput): Unit = ()

  override def shardEnded(shardEnded: ShardEndedInput): Unit = {
    shardEnded.checkpointer.checkpoint()
  }

  override def shutdownRequested(x: ShutdownRequestedInput): Unit = ()

}
