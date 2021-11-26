package tele

import scala.concurrent.duration._

import cats.effect._
import cats.syntax.all._
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest
import software.amazon.awssdk.services.kinesis.model.DeleteStreamRequest
import java.util.UUID
import scala.concurrent.duration.Duration

class ConsumerSpec extends munit.CatsEffectSuite with KinesisSpec {

  implicit val stringSchema = new Schema[String] {
    override def encode(a: String): Array[Byte] = a.getBytes()

    override def decode(bytes: Array[Byte]): Either[DecodingFailure, String] = Right(new String(bytes))

  }

  val streamName = getClass().getName()

  val consumer = ResourceFixture(
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
        .as[String]
        .subscribe
    } yield consumer
  )

  override def munitTimeout: Duration = 120.seconds

  consumer.test("test") { consumer =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())
    val test = for {
      _ <- producer.putRecord("data")
      data <- consumer.take(1).compile.toVector
      _ <- std.Console[IO].println(data)
      // _ <- data.traverse_(d => d.underlying.commit)
    } yield data.collect { case r: DeserializedRecord.WithValue[IO, String] => r.value } == Vector("data")
    test.assert
  }
}
