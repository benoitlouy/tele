package tele

import cats.effect._
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest
import software.amazon.awssdk.services.kinesis.model.DeleteStreamRequest
import cats.data.NonEmptyVector

class ProducerSpec extends munit.CatsEffectSuite with KinesisSpec {

  implicit val stringSchema = new Schema[String] {
    override def encode(a: String): Array[Byte] = a.getBytes()

    override def decode(bytes: Array[Byte]): Either[DecodingFailure, String] = Right(new String(bytes))

  }

  val streamName = getClass().getName()

  val stream =
    Resource.make(
      FutureLift[IO]
        .lift(
          kinesisClient.createStream(CreateStreamRequest.builder().streamName(streamName).shardCount(1).build())
        )
        .void
    )(_ =>
      FutureLift[IO].lift(kinesisClient.deleteStream(DeleteStreamRequest.builder().streamName(streamName).build())).void
    )

  test("putRecord") {
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())
    stream.use { _ =>
      for {
        response <- producer.putRecord("data")
      } yield response.sequenceNumber() != null && response.shardId() != null
    }.assert
  }

  test("putRecords") {
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())
    stream.use { _ =>
      for {
        response <- producer.putRecords(NonEmptyVector.of("data1", "data2", "data3"))
      } yield response.entries.collect { case e: Producer.RichResultEntry.Success[String] => e }.length == 3
    }.assert
  }

}
