package tele

import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.utils.AttributeMap
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.regions.Region
import java.net.URI
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

trait KinesisSpec {
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

}
