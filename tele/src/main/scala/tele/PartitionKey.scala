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

import scala.util.Random

object PartitionKey {
  def random: Any => String = {
    val gen = new Random
    _ => gen.alphanumeric.take(30).mkString
  }

  def random(seed: Long): Any => String = {
    val gen = new Random(seed)
    _ => gen.alphanumeric.take(30).mkString
  }
}
