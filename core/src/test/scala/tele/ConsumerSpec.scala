package tele

// import scala.concurrent.duration._

import cats.data.EitherNec
import cats.effect._
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest
import software.amazon.awssdk.services.kinesis.model.DeleteStreamRequest
import java.util.UUID

class ConsumerSpec extends munit.CatsEffectSuite with KinesisSpec {

  implicit val stringSchema = new Schema[String] {
    override def encode(a: String): Array[Byte] = a.getBytes()

    override def decode(bytes: Array[Byte]): EitherNec[DecodingFailure, String] = Right(new String(bytes))

  }

  val streamName = getClass().getName()

  val consumer = ResourceSuiteLocalFixture(
    "consumer",
    for {
      _ <- Resource.make(
        FutureLift[IO]
          .lift(
            kinesisClient.createStream(CreateStreamRequest.builder().streamName(streamName).shardCount(1).build())
          )
          .void
      )(_ =>
        FutureLift[IO]
          .lift(kinesisClient.deleteStream(DeleteStreamRequest.builder().streamName(streamName).build()))
          .void
          .flatTap(_ => IO(println("stream deleted")))
      )
      consumer <- Consumer
        .make[IO]("test", UUID.randomUUID().toString(), streamName, kinesisClient, dynamoClient, cloudwatchClient)
        .subscribe
    } yield consumer
  )

  override def munitFixtures = List(consumer)

  test("test") {
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())
    for {
      stream <- IO.pure(consumer().take(1).map(println))
      _ <- producer.putRecord("data")
      _ <- stream.compile.drain
    } yield ()

  }
}
