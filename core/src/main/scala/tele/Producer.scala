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

import java.util.concurrent.CompletableFuture

import scala.jdk.CollectionConverters._

import cats.Functor
import cats.data.NonEmptyVector
import cats.effect._
import cats.syntax.all._
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model._

trait FutureLift[F[_]] {
  def lift[A](fa: =>CompletableFuture[A]): F[A]
}

object FutureLift {
  def apply[F[_]](implicit ev: FutureLift[F]): FutureLift[F] = ev

  def catsEffectAsync[F[_]: Async]: FutureLift[F] = new FutureLift[F] {
    override def lift[A](fa: =>CompletableFuture[A]): F[A] = {
      Async[F].fromCompletableFuture(Async[F].delay(fa))
    }
  }
}

trait Producer[F[_], A] {
  import Producer._
  def putRecord(record: A): F[PutRecordResponse]
  def putRecords(records: NonEmptyVector[A]): F[RichPutRecordsResponse[A]]
}

object Producer {
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

  def make[F[_]: Functor: FutureLift, A: Schema](
      client: KinesisAsyncClient,
      stream: String,
      opt: Options[A]
    ): Producer[F, A] =
    new Producer[F, A] {
      override def putRecord(record: A): F[PutRecordResponse] = {
        val bytes = Schema[A].encode(record)
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
          val bytes = Schema[A].encode(record)
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
  }

  object Options {
    private case class OptionImpl[A](partitionKey: A => String) extends Options[A]

    def apply[A](): Options[A] = OptionImpl(PartitionKey.random)
  }
}
