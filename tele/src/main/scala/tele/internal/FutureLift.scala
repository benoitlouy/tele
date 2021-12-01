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

package tele.internal

import java.util.concurrent.CompletableFuture

import cats.effect._

trait FutureLift[F[_]] {
  def lift[A](fa: =>CompletableFuture[A]): F[A]
}

object FutureLift {
  def apply[F[_]](implicit ev: FutureLift[F]): FutureLift[F] = ev

  implicit def catsEffectAsync[F[_]: Async]: FutureLift[F] = new FutureLift[F] {
    override def lift[A](fa: =>CompletableFuture[A]): F[A] = {
      Async[F].fromCompletableFuture(Async[F].delay(fa))
    }
  }
}
