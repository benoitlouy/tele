package tele

import scala.concurrent.duration._

import cats.data.NonEmptyVector
import cats.effect._

class BatcherSpec extends munit.CatsEffectSuite {

  implicit val stringSchema: Schema[String] = new Schema[String] {
    override def encode(a: String): Array[Byte] = a.getBytes()

    override def decode(bytes: Array[Byte]): Either[DecodingFailure, String] = Right(new String(bytes))

  }

  test("batch size limit reached") {
    val result = fs2
      .Stream("data1", "data2", "data3", "data4")
      .covary[IO]
      .through(Batcher.batch(Batcher.Options[String]().withPartitionKey(_ => "a").withMaxBatchSize(15)))
      .compile
      .toVector
      .map(_.collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) })

    result.assertEquals(Vector(NonEmptyVector.of("data1", "data2"), NonEmptyVector.of("data3", "data4")))
  }

  test("batch across chunks") {
    val result = (fs2.Stream("data1") ++ fs2.Stream("data2") ++ fs2.Stream("data3", "data4"))
      .covary[IO]
      .through(Batcher.batch(Batcher.Options[String]().withPartitionKey(_ => "a").withMaxBatchSize(15)))
      .compile
      .toVector
      .map(_.collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) })

    result.assertEquals(Vector(NonEmptyVector.of("data1", "data2"), NonEmptyVector.of("data3", "data4")))
  }

  test("batch count limit reached") {
    val result = fs2
      .Stream("data1", "data2", "data3", "data4")
      .covary[IO]
      .through(Batcher.batch(Batcher.Options[String]().withPartitionKey(_ => "a").withMaxEntryCount(3)))
      .compile
      .toVector
      .map(_.collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) })

    result.assertEquals(Vector(NonEmptyVector.of("data1", "data2", "data3"), NonEmptyVector.one("data4")))
  }

  test("exclude large data") {
    val result = fs2
      .Stream("data1", "data2", "tooLarge", "data3", "data4")
      .covary[IO]
      .through(
        Batcher.batch(Batcher.Options[String]().withPartitionKey(_ => "a").withMaxBatchSize(15).withMaxEntrySize(6))
      )
      .compile
      .toVector
      .map(_.collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) })

    result.assertEquals(Vector(NonEmptyVector.of("data1", "data2"), NonEmptyVector.of("data3", "data4")))
  }

  test("no timeout") {
    val result = (fs2.Stream("data1", "data2") ++ fs2.Stream.sleep_[IO](200.millis) ++ fs2.Stream("data3", "data4"))
      .through(Batcher.batch(Batcher.Options[String]()))
      .compile
      .toVector
      .map(_.collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) })

    result.assertEquals(Vector(NonEmptyVector.of("data1", "data2", "data3", "data4")))
  }

  test("timeout") {
    val result = (fs2.Stream("data1", "data2") ++ fs2.Stream.sleep_[IO](200.millis) ++ fs2.Stream("data3", "data4"))
      .through(Batcher.batch(Batcher.Options[String]().withTimeout(50.millis)))
      .compile
      .toVector
      .map(_.collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) })

    result.assertEquals(Vector(NonEmptyVector.of("data1", "data2"), NonEmptyVector.of("data3", "data4")))
  }
}
