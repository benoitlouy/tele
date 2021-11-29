package tele

import cats.effect._
import cats.data.NonEmptyVector

class ProducerSpec extends munit.CatsEffectSuite with KinesisSpec {

  implicit val stringSchema = new Schema[String] {
    override def encode(a: String): Array[Byte] = a.getBytes()

    override def decode(bytes: Array[Byte]): Either[DecodingFailure, String] = Right(new String(bytes))

  }

  stream.test("putRecord") { streamName =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())

    val test = for {
      response <- producer.putRecord("data")
    } yield response.sequenceNumber() != null && response.shardId() != null

    test.assert
  }

  stream.test("putRecords") { streamName =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())

    val test = for {
      response <- producer.putRecords(NonEmptyVector.of("data1", "data2", "data3"))
    } yield response.entries.collect { case e: Producer.RichResultEntry.Success[String] => e }.length == 3

    test.assert
  }

}
