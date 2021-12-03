package tele

import cats.data.NonEmptyVector
import cats.effect._
import cats.syntax.all._

class ProducerSpec extends munit.CatsEffectSuite with KinesisSpec {

  implicit val stringSchema: Schema[String] = new Schema[String] {
    override def encode(a: String): Array[Byte] = a.getBytes()

    override def decode(bytes: Array[Byte]): Either[DecodingFailure, String] = Right(new String(bytes))

  }

  stream.test("putRecord") { streamName =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())

    val test = for {
      response <- producer.putRecord("data")
    } yield response.sequenceNumber() != null && response.shardId() != null // scalafix:ok

    test.assert
  }

  stream.test("putRecords") { streamName =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())

    val test = for {
      response <- producer.putRecords(NonEmptyVector.of("data1", "data2", "data3"))
    } yield response.entries.collect { case e: Producer.RichResultEntry.Success[String] => e }.length == 3

    test.assert
  }

  stream.test("putRecords sink") { streamName =>
    fs2.Stream
      .emits(List("data1", "data2", "data3"))
      .covary[IO]
      .through(Batcher.batch(Batcher.Options().withMaxEntryCount(2)))
      .collect { case e: Batcher.Batch[String] => e }
      .through(Producer.putRecords(kinesisClient, streamName))
      .compile
      .toVector
      .map(_.map(_.entries.collect { case e: Producer.RichResultEntry.Success[String] => e.record }))
      .flatMap(res => IO(assertEquals(res, Vector(Vector("data1", "data2"), Vector("data3")))))
  }

  stream.test("putRecord sink") { streamName =>
    fs2.Stream
      .emits(List("data1", "data2", "data3"))
      .covary[IO]
      .through(Producer.putRecord(kinesisClient, streamName, Producer.Options()))
      .compile
      .toVector
      .flatMap { res =>
        for {
          _ <- IO(assertEquals(res.map(_.entry), Vector("data1", "data2", "data3")))
          _ <- res.traverse_(response =>
            IO(
              assert(
                response.underlying.sequenceNumber() != null && response.underlying.shardId() != null // scalafix:ok
              )
            )
          )
        } yield ()
      }
  }

}
