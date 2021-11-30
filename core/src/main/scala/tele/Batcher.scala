package tele

import cats.data.NonEmptyVector
import cats.syntax.all._

object Batcher {
  final case class Encoded[A](value: A, bytes: Array[Byte], partitionKey: String, size: Int)

  sealed trait Put[A] extends Product with Serializable

  final case class Batch[A](data: NonEmptyVector[Encoded[A]], size: Int, count: Int) extends Put[A] {
    def add(encoded: Encoded[A]): Batch[A] =
      copy(data = data :+ encoded, size = size + encoded.size, count = count + 1)
  }
  object Batch {
    def one[A](encoded: Encoded[A]): Batch[A] = Batch(NonEmptyVector.one(encoded), encoded.size, 1)
  }

  final case class TooLarge[A](encoded: Encoded[A]) extends Put[A]

  final case class Acc[A](complete: Vector[Batch[A]], cur: Option[Batch[A]]) {
    def all = complete ++ cur
  }
  object Acc {
    def empty[A]: Acc[A] = Acc(Vector.empty, None)
  }

  def batch[F[_], A: Schema](
      opt: Producer.Options[A],
      maxEntrySize: Int = 1000000, // 1MB
      maxBatchSize: Int = 5000000, // 5MB
      maxEntryCount: Int = 500 // 500 entr
    ): fs2.Pipe[F, A, Put[A]] = in => {
    in.mapChunks { chunk =>
      val (tooLarge, fit) = chunk.partitionEither { a =>
        val bytes = Schema[A].encode(a)
        val partitionKey = opt.partitionKey(a)
        val size = bytes.length + partitionKey.getBytes().length
        val encoded = Encoded(a, bytes, partitionKey, size)
        if (size > maxEntrySize) Left(encoded) else Right(encoded)
      }
      val batches = fit
        .foldLeft(Acc.empty[A]) {
          case (Acc(all, Some(cur)), encoded) =>
            if (cur.count + 1 > maxEntryCount || cur.size + encoded.size > maxBatchSize) {
              Acc((all :+ cur), Some(Batch.one(encoded)))
            } else {
              Acc(all, Some(cur.add(encoded)))
            }
          case (Acc(all, None), encoded) => Acc(all, Some(Batch.one(encoded)))
        }
        .all
      fs2.Chunk.vector(batches) ++ tooLarge.map(TooLarge.apply[A])
    }
  }

}
