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

final case class DecodingFailure(msg: String, cause: Throwable) extends Exception(msg, cause)

object DecodingFailure {
  def apply(msg: String): DecodingFailure = DecodingFailure(msg, null) // scalafix:ok
}

trait SchemaEncoder[A] { self =>
  def encode(a: A): Array[Byte]
  def contramap[B](f: B => A): SchemaEncoder[B] = new SchemaEncoder[B] {
    override def encode(b: B): Array[Byte] = self.encode(f(b))
  }
}

object SchemaEncoder {
  def apply[A](implicit ev: SchemaEncoder[A]): SchemaEncoder[A] = ev
}

trait SchemaDecoder[A] { self =>
  def decode(bytes: Array[Byte]): Either[DecodingFailure, A]
  def map[B](f: A => B): SchemaDecoder[B] = new SchemaDecoder[B] {
    override def decode(bytes: Array[Byte]): Either[DecodingFailure, B] = self.decode(bytes).map(f)
  }
}

object SchemaDecoder {
  def apply[A](implicit ev: SchemaDecoder[A]): SchemaDecoder[A] = ev
}

trait Schema[A] extends SchemaEncoder[A] with SchemaDecoder[A] { self =>
  def imap[B](f: A => B)(g: B => A): Schema[B] = new Schema[B] {
    override def encode(b: B): Array[Byte] = self.encode(g(b))
    override def decode(bytes: Array[Byte]): Either[DecodingFailure, B] = self.decode(bytes).map(f)
  }
}

object Schema {
  def apply[A](implicit ev: Schema[A]): Schema[A] = ev
}
