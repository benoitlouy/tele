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

import cats.{ Contravariant, Functor, Invariant }

final case class DecodingFailure(msg: String, cause: Throwable) extends Exception(msg, cause)

object DecodingFailure {
  def apply(msg: String): DecodingFailure = DecodingFailure(msg, null)
}

trait SchemaEncoder[A] {
  def encode(a: A): Array[Byte]
}

object SchemaEncoder {
  def apply[A](implicit ev: SchemaEncoder[A]): SchemaEncoder[A] = ev

  implicit val schemaEncoderContravariant: Contravariant[SchemaEncoder] = new Contravariant[SchemaEncoder] {
    override def contramap[A, B](fa: SchemaEncoder[A])(f: B => A): SchemaEncoder[B] = new SchemaEncoder[B] {
      override def encode(b: B): Array[Byte] = fa.encode(f(b))
    }
  }
}

trait SchemaDecoder[A] {
  def decode(bytes: Array[Byte]): Either[DecodingFailure, A]
}

object SchemaDecoder {
  def apply[A](implicit ev: SchemaDecoder[A]): SchemaDecoder[A] = ev

  implicit val schemaDecoderFunctor: Functor[SchemaDecoder] = new Functor[SchemaDecoder] {
    override def map[A, B](fa: SchemaDecoder[A])(f: A => B): SchemaDecoder[B] = new SchemaDecoder[B] {
      override def decode(bytes: Array[Byte]): Either[DecodingFailure, B] = fa.decode(bytes).map(f)
    }
  }
}

trait Schema[A] extends SchemaEncoder[A] with SchemaDecoder[A]

object Schema {
  def apply[A](implicit ev: Schema[A]): Schema[A] = ev

  implicit val schemaInvariant: Invariant[Schema] = new Invariant[Schema] {
    override def imap[A, B](fa: Schema[A])(f: A => B)(g: B => A): Schema[B] = new Schema[B] {
      override def encode(b: B): Array[Byte] = fa.encode(g(b))

      override def decode(bytes: Array[Byte]): Either[DecodingFailure, B] = fa.decode(bytes).map(f)

    }
  }
}
