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

import scala.jdk.CollectionConverters._

import cats.data.NonEmptyVector
import cats.effect._
import cats.syntax.all._
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model._

import tele.internal.FutureLift

trait Producer[F[_], A] {
  import Producer._
  def putRecord(record: A): F[PutRecordResponse]
  def putRecords(records: NonEmptyVector[A]): F[RichPutRecordsResponse[A]]
}

object Producer {
  final case class RichPutRecordResponse[A](underlying: PutRecordResponse, entry: A)

  final case class RichPutRecordsResponse[A](underlying: PutRecordsResponse, entries: Seq[RichResultEntry[A]])
  object RichPutRecordsResponse {
    def from[A](records: Vector[A], response: PutRecordsResponse): RichPutRecordsResponse[A] =
      RichPutRecordsResponse(
        response,
        response.records().asScala.toList.zipWithIndex.map { case (entry, index) =>
          val record = records.toVector(index)
          RichResultEntry.from(record, entry)
        }
      )
  }

  sealed trait RichResultEntry[A] extends Product with Serializable
  object RichResultEntry {
    def from[A](record: A, entry: PutRecordsResultEntry): RichResultEntry[A] =
      if (entry.shardId() != null) RichResultEntry.Success(record, entry.sequenceNumber(), entry.shardId())
      else RichResultEntry.Failed(record, entry.errorCode(), entry.errorMessage())

    final case class Success[A](record: A, sequenceNumber: String, shardId: String) extends RichResultEntry[A]
    final case class Failed[A](record: A, code: String, message: String) extends RichResultEntry[A]
  }

  def putRecords[F[_]: Async, A](
      client: KinesisAsyncClient,
      stream: String
    ): fs2.Pipe[F, Batcher.Batch[A], RichPutRecordsResponse[A]] = _.evalMap { batch =>
    val entries = batch.data.map { encoded =>
      PutRecordsRequestEntry
        .builder()
        .partitionKey(encoded.partitionKey)
        .data(SdkBytes.fromByteArrayUnsafe(encoded.bytes))
        .build()
    }
    val request = PutRecordsRequest.builder().streamName(stream).records(entries.toVector.asJava).build()
    FutureLift[F]
      .lift(client.putRecords(request))
      .map(response => RichPutRecordsResponse.from(batch.data.map(_.value).toVector, response))
  }

  def putRecord[F[_]: Async, A: SchemaEncoder](
      client: KinesisAsyncClient,
      stream: String,
      opt: Options[A]
    ): fs2.Pipe[F, A, RichPutRecordResponse[A]] =
    _.evalMap { record =>
      val bytes = SchemaEncoder[A].encode(record)
      val partitionKey = opt.partitionKey(record)
      val request = PutRecordRequest
        .builder()
        .streamName(stream)
        .partitionKey(partitionKey)
        .data(SdkBytes.fromByteArrayUnsafe(bytes))
        .build()
      FutureLift[F].lift(client.putRecord(request)).map(response => RichPutRecordResponse(response, record))
    }

  def make[F[_]: Async, A: SchemaEncoder](
      client: KinesisAsyncClient,
      stream: String,
      opt: Options[A]
    ): Producer[F, A] =
    new Producer[F, A] {
      override def putRecord(record: A): F[PutRecordResponse] = {
        val bytes = SchemaEncoder[A].encode(record)
        val partitionKey = opt.partitionKey(record)
        val request = PutRecordRequest
          .builder()
          .streamName(stream)
          .partitionKey(partitionKey)
          .data(SdkBytes.fromByteArrayUnsafe(bytes))
          .build()
        FutureLift[F].lift(client.putRecord(request))
      }

      override def putRecords(records: NonEmptyVector[A]): F[RichPutRecordsResponse[A]] = {
        val entries = records.map { record =>
          val partitionKey = opt.partitionKey(record)
          val bytes = SchemaEncoder[A].encode(record)
          PutRecordsRequestEntry.builder().partitionKey(partitionKey).data(SdkBytes.fromByteArrayUnsafe(bytes)).build()
        }
        val request = PutRecordsRequest.builder().streamName(stream).records(entries.toList.asJava).build()
        FutureLift[F]
          .lift(client.putRecords(request))
          .map(response => RichPutRecordsResponse.from(records.toVector, response))
      }
    }

  sealed trait Options[A] {
    val partitionKey: A => String

    def withPartitionKey(f: A => String): Options[A]
  }

  object Options {
    private case class OptionsImpl[A](partitionKey: A => String) extends Options[A] {
      override def withPartitionKey(f: A => String): Options[A] = copy(partitionKey = f)
    }

    def apply[A](): Options[A] = OptionsImpl(PartitionKey.random)
  }
}
