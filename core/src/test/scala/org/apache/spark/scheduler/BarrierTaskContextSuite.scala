/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.io.File

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import org.apache.spark._
import org.apache.spark.internal.config.Tests.TEST_NO_STAGE_RETRY

class BarrierTaskContextSuite extends SparkFunSuite with LocalSparkContext {

  def initLocalClusterSparkContext(): Unit = {
    val conf = new SparkConf()
      // Init local cluster here so each barrier task runs in a separated process, thus `barrier()`
      // call is actually useful.
      .setMaster("local-cluster[4, 1, 1024]")
      .setAppName("test-cluster")
      .set(TEST_NO_STAGE_RETRY, true)
    sc = new SparkContext(conf)
  }

  test("global sync by barrier() call") {
    initLocalClusterSparkContext()
    val rdd = sc.makeRDD(1 to 10, 4)
    val rdd2 = rdd.barrier().mapPartitions { it =>
      val context = BarrierTaskContext.get()
      // Sleep for a random time before global sync.
      Thread.sleep(Random.nextInt(1000))
      context.barrier()
      Seq(System.currentTimeMillis()).iterator
    }

    val times = rdd2.collect()
    // All the tasks shall finish global sync within a short time slot.
    assert(times.max - times.min <= 1000)
  }

  test("share messages with allGather() call") {
    val conf = new SparkConf()
      .setMaster("local-cluster[4, 1, 1024]")
      .setAppName("test-cluster")
    sc = new SparkContext(conf)
    val rdd = sc.makeRDD(1 to 10, 4)
    val rdd2 = rdd.barrier().mapPartitions { it =>
      val context = BarrierTaskContext.get()
      // Sleep for a random time before global sync.
      Thread.sleep(Random.nextInt(1000))
      // Pass partitionId message in
      val message: String = context.partitionId().toString
      val messages: Array[String] = context.allGather(message)
      messages.toList.iterator
    }
    // Take a sorted list of all the partitionId messages
    val messages = rdd2.collect().head
    // All the task partitionIds are shared
    for((x, i) <- messages.view.zipWithIndex) assert(x.toString == i.toString)
  }

  test("throw exception if we attempt to synchronize with different blocking calls") {
    val conf = new SparkConf()
      .setMaster("local-cluster[4, 1, 1024]")
      .setAppName("test-cluster")
    sc = new SparkContext(conf)
    val rdd = sc.makeRDD(1 to 10, 4)
    val rdd2 = rdd.barrier().mapPartitions { it =>
      val context = BarrierTaskContext.get()
      val partitionId = context.partitionId
      if (partitionId == 0) {
        context.barrier()
      } else {
        context.allGather(partitionId.toString)
      }
      Seq(null).iterator
    }
    val error = intercept[SparkException] {
      rdd2.collect()
    }.getMessage
    assert(
      error.contains("does not match the current synchronized requestMethod") ||
      error.contains("not properly killed")
    )
  }

  test("successively sync with allGather and barrier") {
    val conf = new SparkConf()
      .setMaster("local-cluster[4, 1, 1024]")
      .setAppName("test-cluster")
    sc = new SparkContext(conf)
    val rdd = sc.makeRDD(1 to 10, 4)
    val rdd2 = rdd.barrier().mapPartitions { it =>
      val context = BarrierTaskContext.get()
      // Sleep for a random time before global sync.
      Thread.sleep(Random.nextInt(1000))
      context.barrier()
      val time1 = System.currentTimeMillis()
      // Sleep for a random time before global sync.
      Thread.sleep(Random.nextInt(1000))
      // Pass partitionId message in
      val message = context.partitionId().toString
      val messages = context.allGather(message)
      val time2 = System.currentTimeMillis()
      Seq((time1, time2)).iterator
    }
    val times = rdd2.collect()
    // All the tasks shall finish the first round of global sync within a short time slot.
    val times1 = times.map(_._1)
    assert(times1.max - times1.min <= 1000)

    // All the tasks shall finish the second round of global sync within a short time slot.
    val times2 = times.map(_._2)
    assert(times2.max - times2.min <= 1000)
  }

  test("support multiple barrier() call within a single task") {
    initLocalClusterSparkContext()
    val rdd = sc.makeRDD(1 to 10, 4)
    val rdd2 = rdd.barrier().mapPartitions { it =>
      val context = BarrierTaskContext.get()
      // Sleep for a random time before global sync.
      Thread.sleep(Random.nextInt(1000))
      context.barrier()
      val time1 = System.currentTimeMillis()
      // Sleep for a random time between two global syncs.
      Thread.sleep(Random.nextInt(1000))
      context.barrier()
      val time2 = System.currentTimeMillis()
      Seq((time1, time2)).iterator
    }

    val times = rdd2.collect()
    // All the tasks shall finish the first round of global sync within a short time slot.
    val times1 = times.map(_._1)
    assert(times1.max - times1.min <= 1000)

    // All the tasks shall finish the second round of global sync within a short time slot.
    val times2 = times.map(_._2)
    assert(times2.max - times2.min <= 1000)
  }

  test("throw exception on barrier() call timeout") {
    initLocalClusterSparkContext()
    sc.conf.set("spark.barrier.sync.timeout", "1")
    val rdd = sc.makeRDD(1 to 10, 4)
    val rdd2 = rdd.barrier().mapPartitions { it =>
      val context = BarrierTaskContext.get()
      // Task 3 shall sleep 2000ms to ensure barrier() call timeout
      if (context.taskAttemptId == 3) {
        Thread.sleep(2000)
      }
      context.barrier()
      it
    }

    val error = intercept[SparkException] {
      rdd2.collect()
    }.getMessage
    assert(error.contains("The coordinator didn't get all barrier sync requests"))
    assert(error.contains("within 1 second(s)"))
  }

  test("throw exception if barrier() call doesn't happen on every task") {
    initLocalClusterSparkContext()
    sc.conf.set("spark.barrier.sync.timeout", "1")
    val rdd = sc.makeRDD(1 to 10, 4)
    val rdd2 = rdd.barrier().mapPartitions { it =>
      val context = BarrierTaskContext.get()
      if (context.taskAttemptId != 0) {
        context.barrier()
      }
      it
    }

    val error = intercept[SparkException] {
      rdd2.collect()
    }.getMessage
    assert(error.contains("The coordinator didn't get all barrier sync requests"))
    assert(error.contains("within 1 second(s)"))
  }

  test("throw exception if the number of barrier() calls are not the same on every task") {
    initLocalClusterSparkContext()
    sc.conf.set("spark.barrier.sync.timeout", "1")
    val rdd = sc.makeRDD(1 to 10, 4)
    val rdd2 = rdd.barrier().mapPartitions { it =>
      val context = BarrierTaskContext.get()
      try {
        if (context.taskAttemptId == 0) {
          // Due to some non-obvious reason, the code can trigger an Exception and skip the
          // following statements within the try ... catch block, including the first barrier()
          // call.
          throw new SparkException("test")
        }
        context.barrier()
      } catch {
        case e: Exception => // Do nothing
      }
      context.barrier()
      it
    }

    val error = intercept[SparkException] {
      rdd2.collect()
    }.getMessage
    assert(error.contains("The coordinator didn't get all barrier sync requests"))
    assert(error.contains("within 1 second(s)"))
  }

  def testBarrierTaskKilled(interruptOnKill: Boolean): Unit = {
    withTempDir { dir =>
      val killedFlagFile = "barrier.task.killed"
      val rdd = sc.makeRDD(Seq(0, 1), 2)
      val rdd2 = rdd.barrier().mapPartitions { it =>
        val context = BarrierTaskContext.get()
        if (context.partitionId() == 0) {
          try {
            context.barrier()
          } catch {
            case _: TaskKilledException =>
              new File(dir, killedFlagFile).createNewFile()
          }
        } else {
          Thread.sleep(5000)
          context.barrier()
        }
        it
      }

      val listener = new SparkListener {
        override def onTaskStart(taskStart: SparkListenerTaskStart): Unit = {
          val partitionId = taskStart.taskInfo.index
          if (partitionId == 0) {
            new Thread {
              override def run: Unit = {
                Thread.sleep(1000)
                sc.killTaskAttempt(taskStart.taskInfo.taskId, interruptThread = interruptOnKill)
              }
            }.start()
          }
        }
      }
      sc.addSparkListener(listener)

      intercept[SparkException] {
        rdd2.collect()
      }

      sc.removeSparkListener(listener)

      assert(new File(dir, killedFlagFile).exists(), "Expect barrier task being killed.")
    }
  }

  test("barrier task killed, no interrupt") {
    initLocalClusterSparkContext()
    testBarrierTaskKilled(interruptOnKill = false)
  }

  test("barrier task killed, interrupt") {
    initLocalClusterSparkContext()
    testBarrierTaskKilled(interruptOnKill = true)
  }
}
