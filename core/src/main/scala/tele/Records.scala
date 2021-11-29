/*
 * Copyright 2021 Benoit Louy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
