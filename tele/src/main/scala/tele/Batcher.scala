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

import scala.concurrent.duration.FiniteDuration

import cats.data.NonEmptyVector
import cats.effect._
import cats.syntax.all._

object Batcher {
  final case class Encoded[A](value: A, bytes: Array[Byte], partitionKey: String, size: Int)

  sealed trait Result[A] extends Product with Serializable

  final case class Batch[A](data: NonEmptyVector[Encoded[A]], size: Int, count: Int) extends Result[A] {
    def add(encoded: Encoded[A]): Batch[A] =
      copy(data = data :+ encoded, size = size + encoded.size, count = count + 1)
  }
  object Batch {
    def one[A](encoded: Encoded[A]): Batch[A] = Batch(NonEmptyVector.one(encoded), encoded.size, 1)
  }

  final case class TooLarge[A](encoded: Encoded[A]) extends Result[A]

  private final case class Acc[A](complete: Vector[Batch[A]], next: Option[Batch[A]])

  def batch[F[_]: Temporal, A: SchemaEncoder](opt: Options[A]): fs2.Pipe[F, A, Result[A]] = {

    def makeBatches(as: fs2.Chunk[A], next: Option[Batch[A]]): (fs2.Chunk[Result[A]], Option[Batch[A]]) = {
      val (tooLarge, fit) = as.partitionEither { a =>
        val bytes = SchemaEncoder[A].encode(a)
        val partitionKey = opt.partitionKey(a)
        val size = bytes.length + partitionKey.getBytes().length
        val encoded = Encoded(a, bytes, partitionKey, size)
        if (size > opt.maxEntrySize) Left(encoded) else Right(encoded)
      }
      val acc = fit.foldLeft(Acc(Vector.empty, next)) {
        case (Acc(all, Some(cur)), encoded) =>
          if (cur.count + 1 > opt.maxEntryCount || cur.size + encoded.size > opt.maxBatchSize) {
            Acc((all :+ cur), Some(Batch.one(encoded)))
          } else {
            Acc(all, Some(cur.add(encoded)))
          }
        case (Acc(all, None), encoded) => Acc(all, Some(Batch.one(encoded)))
      }
      (fs2.Chunk.vector(acc.complete) ++ tooLarge.map(TooLarge.apply)) -> acc.next
    }

    def go(s: fs2.Stream[F, A], cur: Option[Batch[A]]): fs2.Pull[F, Result[A], Unit] = {
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          val (complete, next) = makeBatches(hd, cur)
          fs2.Pull.output(complete) >> go(tl, next)
        case None =>
          cur match {
            case None => fs2.Pull.done
            case Some(batch) => fs2.Pull.output1(batch)
          }
      }
    }

    def timed(s: fs2.Stream[F, A], timeout: FiniteDuration): fs2.Pull[F, Result[A], Unit] = {
      s.pull.timed { timedPull =>
        def go(timedPull: fs2.Pull.Timed[F, A], cur: Option[Batch[A]]): fs2.Pull[F, Result[A], Unit] = {
          timedPull.timeout(timeout) >> timedPull.uncons.flatMap {
            case Some((Right(hd), tl)) =>
              val (complete, next) = makeBatches(hd, cur)
              fs2.Pull.output(complete) >> go(tl, next)
            case Some((Left(_), tl)) =>
              cur match {
                case None => go(tl, None)
                case Some(batch) => fs2.Pull.output1(batch) >> go(tl, None)
              }
            case None =>
              cur match {
                case None => fs2.Pull.done
                case Some(batch) => fs2.Pull.output1(batch)
              }
          }
        }
        go(timedPull, None)
      }
    }

    opt.timeout match {
      case None => in => go(in, None).stream
      case Some(timeout) => in => timed(in, timeout).stream
    }
  }

  sealed trait Options[A] {
    val partitionKey: A => String
    val maxEntrySize: Int
    val maxBatchSize: Int
    val maxEntryCount: Int
    val timeout: Option[FiniteDuration]

    def withPartitionKey(f: A => String): Options[A]
    def withMaxEntrySize(maxEntrySize: Int): Options[A]
    def withMaxBatchSize(maxBatchSize: Int): Options[A]
    def withMaxEntryCount(maxEntryCount: Int): Options[A]
    def withTimeout(timeout: FiniteDuration): Options[A]
  }

  object Options {
    private case class OptionsImpl[A](
        partitionKey: A => String,
        maxEntrySize: Int,
        maxBatchSize: Int,
        maxEntryCount: Int,
        timeout: Option[FiniteDuration])
      extends Options[A] {
      override def withPartitionKey(f: A => String): Options[A] = copy(partitionKey = f)

      override def withMaxEntrySize(maxEntrySize: Int): Options[A] = copy(maxEntrySize = maxEntrySize)

      override def withMaxBatchSize(maxBatchSize: Int): Options[A] = copy(maxBatchSize = maxBatchSize)

      override def withMaxEntryCount(maxEntryCount: Int): Options[A] = copy(maxEntryCount = maxEntryCount)

      override def withTimeout(timeout: FiniteDuration): Options[A] = copy(timeout = Some(timeout))
    }

    def apply[A](): Options[A] =
      OptionsImpl(
        PartitionKey.random,
        maxEntrySize = 1000000,
        maxBatchSize = 5000000,
        maxEntryCount = 500,
        timeout = None
      )
  }

}
