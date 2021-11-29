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
