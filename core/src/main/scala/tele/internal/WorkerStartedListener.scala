package tele.internal

import cats.effect._
import software.amazon.kinesis.coordinator.WorkerStateChangeListener

final case class WorkerStartedListener[F[_]](started: Deferred[F, Unit], dispatcher: std.Dispatcher[F])
  extends WorkerStateChangeListener {
  override def onWorkerStateChange(newState: WorkerStateChangeListener.WorkerState): Unit = {
    println(s"NEWSTATE $newState")
    if (newState == WorkerStateChangeListener.WorkerState.STARTED) {
      val _ = dispatcher.unsafeRunSync(started.complete(()))
    }
  }
  override def onAllInitializationAttemptsFailed(e: Throwable): Unit = throw e // scalafix:ok
}
