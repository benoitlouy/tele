package tele

import java.util.UUID

import scala.concurrent.duration._

import cats.data.NonEmptyVector
import cats.effect._
import cats.syntax.all._

class ConsumerSpec extends munit.CatsEffectSuite with KinesisSpec {

  implicit val stringSchema = new Schema[String] {
    override def encode(a: String): Array[Byte] = a.getBytes()

    override def decode(bytes: Array[Byte]): Either[DecodingFailure, String] = Right(new String(bytes))

  }

  override def munitTimeout: Duration = 120.seconds

  val opt =
    Consumer
      .Options()
      .withRetrievalMode(Consumer.Options.Polling)
      .withInitialPosition(Consumer.Options.TrimHorizon)
      .withParentShardPollInterval(1.second)
      .withShardSyncInterval(1.second)

  stream.test("consume single record") { streamName =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())
    val consumer = Consumer
      .make[IO](
        2,
        "test",
        UUID.randomUUID().toString(),
        streamName,
        kinesisClient,
        dynamoClient,
        cloudwatchClient,
        opt
      )
      .withSchema[String]

    val test = consumer.subscribe.use { records =>
      for {
        _ <- producer.putRecord("data")
        data <- records.take(1).compile.toVector
      } yield data.collect { case r: CommitableRecord.WithValue[IO, String] => r.value } == Vector("data")
    }

    test.assert
  }

  stream.test("consume multiple records") { streamName =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())
    val consumer = Consumer
      .make[IO](
        2,
        "test",
        UUID.randomUUID().toString(),
        streamName,
        kinesisClient,
        dynamoClient,
        cloudwatchClient,
        opt
      )
      .withSchema[String]

    val test = consumer.subscribe.use { records =>
      for {
        _ <- producer.putRecords(NonEmptyVector.of("data1", "data2"))
        data <- records.take(2).compile.toVector
      } yield data.collect { case r: CommitableRecord.WithValue[IO, String] => r.value } == Vector("data1", "data2")
    }

    test.assert
  }

  stream.test("without checkpointing") { streamName =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())
    val consumer = Consumer
      .make[IO](
        2,
        "test",
        UUID.randomUUID().toString(),
        streamName,
        kinesisClient,
        dynamoClient,
        cloudwatchClient,
        opt
      )
      .withSchema[String]

    val step1 = consumer.subscribe.use { records =>
      for {
        _ <- producer.putRecords(NonEmptyVector.of("data1", "data2"))
        data <- records.take(1).compile.toVector
      } yield data
    }

    val step2 = consumer.subscribe.use { records =>
      records.take(1).compile.toVector
    }

    val test = for {
      a <- step1
      b <- step2
    } yield (a ++ b).collect { case r: CommitableRecord.WithValue[IO, String] => r.value } == Vector("data1", "data1")

    test.assert
  }

  stream.test("with checkpointing") { streamName =>
    val producer = Producer.make[IO, String](kinesisClient, streamName, Producer.Options())
    val consumer = Consumer
      .make[IO](
        2,
        "test",
        UUID.randomUUID().toString(),
        streamName,
        kinesisClient,
        dynamoClient,
        cloudwatchClient,
        opt
      )
      .withSchema[String]

    val step1 = consumer.subscribe.use { records =>
      for {
        _ <- producer.putRecords(NonEmptyVector.of("data1", "data2"))
        data <- records.take(1).compile.toVector
        _ <- data.traverse_(_.commit)
      } yield data
    }

    val step2 = consumer.subscribe.use { records =>
      for {
        data <- records.take(1).compile.toVector
        _ <- data.traverse_(_.commit)
      } yield data
    }

    val test = for {
      a <- step1
      b <- step2
    } yield (a ++ b).collect { case r: CommitableRecord.WithValue[IO, String] => r.value } == Vector("data1", "data2")

    test.assert
  }
}
