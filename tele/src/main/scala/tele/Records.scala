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

sealed trait Record[F[_]] {
  def record: KinesisClientRecord
  def commit: F[Unit]
}

object Record {
  sealed trait WithData[F[_], +A] extends Record[F] {
    def map[B](f: A => B): WithData[F, B] = this match {
      case v: CommitableRecord.WithValue[F, a] => v.map(f)
      case e: CommitableRecord.WithError[F] => e
    }
  }
}

final case class CommitableRecord[F[_]](record: KinesisClientRecord, commit: F[Unit]) extends Record[F] {
  def withValue[A](value: A): CommitableRecord.WithValue[F, A] = CommitableRecord.WithValue(value, record, commit)
  def withError(error: DecodingFailure): CommitableRecord.WithError[F] =
    CommitableRecord.WithError(error, record, commit)
}

object CommitableRecord {
  final case class WithValue[F[_], +A](value: A, record: KinesisClientRecord, commit: F[Unit])
    extends Record.WithData[F, A] {
    override def map[B](f: A => B): WithValue[F, B] = CommitableRecord.WithValue(f(value), record, commit)
  }

  object WithValue {
    object deriveSchemaEncoder {
      implicit def withValueSchemaEncoder[F[_], A: SchemaEncoder]: SchemaEncoder[WithValue[F, A]] =
        new SchemaEncoder[WithValue[F, A]] {
          override def encode(a: WithValue[F, A]): Array[Byte] = SchemaEncoder[A].encode(a.value)
        }
    }
  }

  final case class WithError[F[_]](error: DecodingFailure, record: KinesisClientRecord, commit: F[Unit])
    extends Record.WithData[F, Nothing]
}
