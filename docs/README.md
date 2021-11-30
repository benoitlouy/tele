# tele

## Overview

tele is a Kinesis client providing fs2 interfaces to produce and consume from Kinesis streams.

```scala
trait Producer[F[_], A] {
  def putRecord(record: A): F[PutRecordResponse]
  def putRecords(records: NonEmptyVector[A]): F[RichPutRecordsResponse[A]]
}

trait Consumer[F[_], A] { self =>
  def subscribe: Resource[F, fs2.Stream[F, A]]
}
```

## Using

```scala
libraryDependencies +=  "@ORGANIZATION@" %% "@NAME@" % "@VERSION@"
```

## Examples

For the following example we will be publishing and consuming `User` records 
represented as


```scala mdoc:reset-object
final case class User(name: String, age: Int)
```

```scala mdoc:invisible
import cats.effect.unsafe.implicits.global
import java.net.URI
import software.amazon.awssdk.auth.credentials.{ AwsCredentials, AwsCredentialsProvider }
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.utils.AttributeMap

case class AwsCreds(accessKey: String, secretKey: String) extends AwsCredentials with AwsCredentialsProvider {
  override def accessKeyId(): String = accessKey
  override def secretAccessKey(): String = secretKey
  override def resolveCredentials(): AwsCredentials = this
}

object AwsCreds {
  val LocalCreds: AwsCreds =
    AwsCreds("mock-kinesis-access-key", "mock-kinesis-secret-key")
}

val trustAllCertificates =
  AttributeMap
    .builder()
    .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, java.lang.Boolean.TRUE)
    .build()

val nettyClient: SdkAsyncHttpClient = NettyNioAsyncHttpClient.builder().buildWithDefaults(trustAllCertificates)

val kinesisClient =
  KinesisAsyncClient
    .builder()
    .httpClient(nettyClient)
    .region(Region.US_EAST_1)
    .credentialsProvider(AwsCreds.LocalCreds)
    .endpointOverride(URI.create(s"https://localhost:4567"))
    .build()

val cloudwatchClient =
  CloudWatchAsyncClient
    .builder()
    .httpClient(nettyClient)
    .region(Region.US_EAST_1)
    .credentialsProvider(AwsCreds.LocalCreds)
    .endpointOverride(URI.create(s"https://localhost:4566")) // localstack port
    .build()

val dynamodbClient =
  DynamoDbAsyncClient
    .builder()
    .httpClient(nettyClient)
    .region(Region.US_EAST_1)
    .credentialsProvider(AwsCreds.LocalCreds)
    .endpointOverride(URI.create(s"http://localhost:8000")) // dynamodb-local port
    .build()
```

We also need to provide a way to serialize this type. This is accomplished by 
declaring a implicit instance of `Schema[User]`. In this example we will be 
using circe to serialize to JSON.

```scala mdoc:silent
import cats.syntax.all._
import io.circe.generic.auto._, io.circe.parser, io.circe.syntax._
import tele.Schema

implicit val userSchema: Schema[User] = new Schema[User] {
  override def encode(a: User): Array[Byte] = a.asJson.noSpaces.getBytes()

  override def decode(bytes: Array[Byte]): Either[tele.DecodingFailure, User] =
    parser.decode[User](new String(bytes))
      .leftMap(e => tele.DecodingFailure("failed decoding User", e))

}
```

### Producer
We create a stream containing a few users:
```scala mdoc
val users = fs2.Stream(User("Alice", 35), User("Bob", 28), User("Carol", 24))
```

tele provides 2 ways of publishing records to kinesis:
  - using `putRecord` which publishes records one by one.
  - using `putRecords` which publishes records by batch.

#### `putRecord`
```scala mdoc:compile-only
import cats.effect._
import tele.Producer

val result: Vector[Producer.RichPutRecordResponse[User]] =
  users
    .covary[IO]
    .through(Producer.putRecord(kinesisClient, "user-stream", Producer.Options()))
    .compile
    .toVector
    .unsafeRunSync()
```

#### `putRecords`
Before being able to call `putRecords`, records must be batched. For this purpose
`tele` provides a `Batcher` which will group records in `Batcher.Batch[A]`
where `A` is the record type (`User` in this example).

Because Kinesis limits the size of individual records, the batcher will detect
large records and will also produce instances of `Batcher.TooLarge[A]`.

`Batcher.Batch[A]` and `Batcher.TooLarge[A]` form the ADT `Batcher.Result[A]`
and the batcher is an fs2.Pipe with the type `fs2.Pipe[F, A, Result[A]]`

```scala mdoc:compile-only
import cats.effect._
import tele.{ Batcher, Producer }

val result: Vector[Producer.RichPutRecordsResponse[User]] =
  users
    .covary[IO]
    .through(Batcher.batch(Batcher.Options()))
    .collect { case batch: Batcher.Batch[User] => batch }
    .through(Producer.putRecords(kinesisClient, "user-stream"))
    .compile
    .toVector
    .unsafeRunSync()
```

### Consumer

```scala mdoc:compile-only
import cats.effect._
import tele.{ Consumer, Record }

val consumer = Consumer.make[IO](
  capacity = 500,
  "appName",
  "workerId",
  "user-stream",
  kinesisClient,
  dynamodbClient,
  cloudwatchClient,
  Consumer.Options()
).withSchema[User]

val result: Vector[Record.WithData[IO, User]] = consumer.subscribe.use { users =>
  users.take(3).compile.toVector
}.unsafeRunSync()
```

`Record.WithData[F[_], A]` is an ADT which indicate if the data received could be
deserialized to an instance of `A`. The members of this ADT are:
  - `CommitableRecord.WithValue[F[_], A]` when deserialization was successful
  - `CommitableRecord.WithError[F[_]]` when deserialization failed
