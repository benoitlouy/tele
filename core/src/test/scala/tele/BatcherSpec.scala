package tele

import cats.data.NonEmptyVector

class BatcherSpec extends munit.FunSuite {

  implicit val stringSchema = new Schema[String] {
    override def encode(a: String): Array[Byte] = a.getBytes()

    override def decode(bytes: Array[Byte]): Either[DecodingFailure, String] = Right(new String(bytes))

  }

  test("batch size limit reached") {
    val result = fs2.Stream
      .emits(Vector("data1", "data2", "data3", "data4"))
      .through(Batcher.batch(Producer.Options[String]().withPartitionKey(_ => "a"), maxBatchSize = 15))
      .compile
      .toVector
      .collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) }

    assertEquals(result, Vector(NonEmptyVector.of("data1", "data2"), NonEmptyVector.of("data3", "data4")))
  }

  test("batch count limit reached") {
    val result = fs2.Stream
      .emits(Vector("data1", "data2", "data3", "data4"))
      .through(Batcher.batch(Producer.Options[String]().withPartitionKey(_ => "a"), maxEntryCount = 3))
      .compile
      .toVector
      .collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) }

    assertEquals(result, Vector(NonEmptyVector.of("data1", "data2", "data3"), NonEmptyVector.one("data4")))
  }

  test("exclude large data") {
    val result = fs2.Stream
      .emits(Vector("data1", "data2", "tooLarge", "data3", "data4"))
      .through(
        Batcher.batch(Producer.Options[String]().withPartitionKey(_ => "a"), maxBatchSize = 15, maxEntrySize = 6)
      )
      .compile
      .toVector
      .collect { case Batcher.Batch(buf, _, _) => buf.map(_.value) }

    assertEquals(result, Vector(NonEmptyVector.of("data1", "data2"), NonEmptyVector.of("data3", "data4")))
  }
}
