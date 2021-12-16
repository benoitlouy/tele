package tele

import java.util.UUID

import scala.concurrent.duration._

import cats.effect._
import cats.syntax.all._
import io.circe._
import io.circe.syntax._

class ForwarderSpec extends munit.CatsEffectSuite with KinesisSpec {

  case class User(name: String, age: Int) {
    def withId(id: UUID): User.WithId = User.WithId(id, this)
  }
  object User {

    implicit val encoder: Encoder[User] = user => Json.obj("name" -> user.name.asJson, "age" -> user.age.asJson)
    implicit val decoder: Decoder[User] = { cur =>
      for {
        name <- cur.downField("name").as[String]
        age <- cur.downField("age").as[Int]
      } yield User(name, age)
    }

    implicit val schema: Schema[User] = new Schema[User] {
      override def encode(a: User): Array[Byte] = a.asJson.noSpaces.getBytes()

      override def decode(bytes: Array[Byte]): Either[tele.DecodingFailure, User] =
        parser.decode[User](new String(bytes)).leftMap(e => tele.DecodingFailure("failed decoding User", e))

    }

    case class WithId(id: UUID, user: User)

    object WithId {
      implicit val encoder: Encoder[WithId] = withId => Json.obj("id" -> withId.id.asJson, "user" -> withId.user.asJson)
      implicit val decoder: Decoder[WithId] = { cur =>
        for {
          id <- cur.downField("id").as[UUID]
          user <- cur.downField("user").as[User]
        } yield WithId(id, user)
      }

      implicit val schema: Schema[WithId] = new Schema[WithId] {
        override def encode(a: WithId): Array[Byte] = a.asJson.noSpaces.getBytes()

        override def decode(bytes: Array[Byte]): Either[tele.DecodingFailure, WithId] =
          parser.decode[WithId](new String(bytes)).leftMap(e => tele.DecodingFailure("failed decoding User.WithId", e))

      }
    }

  }

  override def munitTimeout: Duration = 120.seconds

  streams2.test("test") { case (src, dst) =>
    val opt =
      Consumer
        .Options()
        .withRetrievalMode(Consumer.Options.Polling)
        .withInitialPosition(Consumer.Options.TrimHorizon)
        .withParentShardPollInterval(1.second)
        .withShardSyncInterval(1.second)

    val consumerSrc = Consumer
      .make[IO](
        100,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        src,
        kinesisClient,
        dynamoClient,
        cloudwatchClient,
        opt
      )
      .withSchema[User]

    val consumerDst = Consumer
      .make[IO](
        100,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        dst,
        kinesisClient,
        dynamoClient,
        cloudwatchClient,
        opt
      )
      .withSchema[User.WithId]

    consumerSrc.subscribe.parProduct(consumerDst.subscribe).use { case (srcStream, dstStream) =>
      val publish = fs2
        .Stream(User("bob", 42))
        .covary[IO]
        .through(Batcher.batch(Batcher.Options()))
        .collect { case e: Batcher.Batch[User] => e }
        .through(Producer.putRecords(kinesisClient, src))

      import CommitableRecord.WithValue.deriveSchemaEncoder._

      val forward = srcStream
        .collect { case r @ CommitableRecord.WithValue(_, _, _) => r }
        .map(_.map(_.withId(UUID.randomUUID)))
        .take(1)
        .through(Batcher.batch(Batcher.Options()))
        .collect { case e: Batcher.Batch[CommitableRecord.WithValue[IO, _]] => e }
        .through(Producer.putRecords(kinesisClient, dst))

      for {
        data <- dstStream.concurrently(forward).concurrently(publish).take(1).compile.toVector
        _ <- IO(
          assertEquals(
            data.collect { case CommitableRecord.WithValue(value, _, _) => value.user },
            Vector(User("bob", 42))
          )
        )
      } yield ()
    }

  }

}
