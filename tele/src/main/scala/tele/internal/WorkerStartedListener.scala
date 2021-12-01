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

import cats.effect._
import software.amazon.kinesis.coordinator.WorkerStateChangeListener

private[tele] final case class WorkerStartedListener[F[_]](
    started: Deferred[F, Unit],
    dispatcher: std.Dispatcher[F],
    delegate: WorkerStateChangeListener)
  extends WorkerStateChangeListener {
  override def onWorkerStateChange(newState: WorkerStateChangeListener.WorkerState): Unit = {
    if (newState == WorkerStateChangeListener.WorkerState.STARTED) {
      val _ = dispatcher.unsafeRunSync(started.complete(()))
    }
    delegate.onWorkerStateChange(newState)
  }
  override def onAllInitializationAttemptsFailed(e: Throwable): Unit = {
    delegate.onAllInitializationAttemptsFailed(e)
  }
}
