package tele

import java.net.URI

import scala.util.Random

import cats.effect._
import software.amazon.awssdk.auth.credentials.{ AwsCredentials, AwsCredentialsProvider }
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.{ CreateStreamRequest, DeleteStreamRequest }
import software.amazon.awssdk.utils.AttributeMap

import tele.internal.FutureLift

trait KinesisSpec { self: munit.CatsEffectSuite =>
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

  val dynamoClient =
    DynamoDbAsyncClient
      .builder()
      .httpClient(nettyClient)
      .region(Region.US_EAST_1)
      .credentialsProvider(AwsCreds.LocalCreds)
      .endpointOverride(URI.create(s"http://localhost:8000")) // dynamodb-local port
      .build()

  val stream = ResourceFixture {
    val gen = new Random
    for {
      name <- Resource.eval(IO(gen.alphanumeric.take(30).mkString))
      _ <- Resource.make(
        FutureLift[IO]
          .lift(
            kinesisClient.createStream(CreateStreamRequest.builder().streamName(name).shardCount(1).build())
          )
          .void
      )(_ =>
        FutureLift[IO]
          .lift(kinesisClient.deleteStream(DeleteStreamRequest.builder().streamName(name).build()))
          .void
      )
    } yield name
  }

}
