package tele

import software.amazon.kinesis.retrieval.KinesisClientRecord

final case class CommitableRecord[F[_]](record: KinesisClientRecord, commit: F[Unit]) {
  def withValue[A](value: A): DeserializedRecord.WithValue[F, A] = DeserializedRecord.WithValue(value, this)
  def withError(error: DecodingFailure): DeserializedRecord.WithError[F] = DeserializedRecord.WithError(error, this)
}

sealed abstract class DeserializedRecord[F[_], +A] extends Product with Serializable {
  val underlying: CommitableRecord[F]
}
object DeserializedRecord {
  final case class WithValue[F[_], A](value: A, underlying: CommitableRecord[F]) extends DeserializedRecord[F, A]
  final case class WithError[F[_]](error: DecodingFailure, underlying: CommitableRecord[F])
    extends DeserializedRecord[F, Nothing]
}
