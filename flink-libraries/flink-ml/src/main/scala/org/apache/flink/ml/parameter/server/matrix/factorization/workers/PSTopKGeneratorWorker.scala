package org.apache.flink.ml.parameter.server.matrix.factorization.workers

import org.apache.flink.ml.parameter.server.ParameterServerClient
import org.apache.flink.ml.parameter.server.matrix.factorization.pruning.LEMPPruningFunctions._
import org.apache.flink.ml.parameter.server.matrix.factorization.pruning._
import org.apache.flink.ml.parameter.server.matrix.factorization.utils.RichRating
import org.apache.flink.ml.parameter.server.matrix.factorization.utils.Utils._
import org.apache.flink.ml.parameter.server.matrix.factorization.utils.Vector._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.control.Breaks._

class PSTopKGeneratorWorker(workerK: Int,
                            bucketSize: Int,
                            workerParallelism: Int,
                            pruning: LEMPPruningStrategy)
  extends BaseMFWorkerLogic[RichRating, LengthAndVector, TopKOutput] {

  val itemIdsDescendingByLength = new mutable.TreeSet[(Double, ItemId)]()(implicitly[Ordering[(Double, ItemId)]].reverse)
  val itemIdsBuffer = new ArrayBuffer[ItemId] // needed for negative sample generation, where random access to a random element is required
  val ratingBuffer = new mutable.HashMap[UserId, mutable.Queue[RichRating]]()
  lazy val buckets: List[List[(VectorLength, ItemId)]] = itemIdsDescendingByLength.toList.grouped(bucketSize).toList

  def invalidUser(value: VectorLength): Boolean = value == -1

  override def onRecv(data: RichRating, ps: ParameterServerClient[LengthAndVector, TopKOutput]): Unit = {
    ratingBuffer synchronized {
      ratingBuffer.getOrElseUpdate(data.user, mutable.Queue[RichRating]()).enqueue(data)
    }
    ps.pull(data.user)
  }


  override def onPullRecv(paramId: UserId, userAndLen: LengthAndVector,
                          ps: ParameterServerClient[LengthAndVector, TopKOutput]): Unit = {
    val rate = ratingBuffer synchronized {
      ratingBuffer(paramId).dequeue()
    }

    if (invalidUser(userAndLen._1)) {
      ps.output(rate, newTopKQueue())
      return
    }

    val userVectorDirection = userAndLen._2
    val userVectorLength = userAndLen._1

    val topK = newTopKQueue()

    // focus coordinate for coord pruning test
    val focus = ((1 until userVectorDirection.length) :\ 0) { (i, f) =>
      if (userVectorDirection(i) * userVectorDirection(i) > userVectorDirection(f) * userVectorDirection(f))
        i
      else
        f
    }

    // focus coordinate set for incremental pruning test
    val focusSet = Array.range(0, userVectorDirection.length - 1)
      .sortBy{ x => -userVectorDirection(x) * userVectorDirection(x) }
      .take(pruning match {
        case INCR(x)=> x
        case LI(x, _)=> x
        case _=> 0
      })

    breakable{
      for(currentBucket <- buckets){
        if (!((topK.length < workerK) || (currentBucket.head._1 * userVectorLength > topK.head._1))){
          break()
        }
        val theta = if (topK.length < workerK) 0.0 else topK.head._1
        val theta_b_q = theta / (currentBucket.head._1 * userVectorLength)
        val vectors = currentBucket.map(x => (x._2, model(x._2)))

        val candidates = vectors.filter(pruning match {
          case LENGTH() => lengthPruning(theta / userVectorLength)
          case COORD() => coordPruning(focus, userAndLen, theta_b_q)
          case INCR(_) => incrPruning(focusSet, userAndLen, theta)
          case LC(threshold) =>
            if (currentBucket.head._1 > currentBucket.last._1 * threshold)
              lengthPruning(theta / userVectorLength)
            else
              coordPruning(focus, userAndLen, theta_b_q)
          case LI(_, threshold) =>
            if (currentBucket.head._1 > currentBucket.last._1 * threshold)
              lengthPruning(theta / userVectorLength)
            else
              incrPruning(focusSet, userAndLen, theta)
        })

        for (item <- candidates) {
          val userItemDotProduct = dotProduct(userVectorDirection, item._2._2)

          if (topK.size < workerK) {
            topK += ((userItemDotProduct, item._1))
          }
          else {
            if (topK.head._1 < userItemDotProduct) {
              topK.dequeue
              topK += ((userItemDotProduct, item._1))
            }
          }
        }
      }
    }

    ps.output((rate, topK))
  }

  override def updateModel(id: Int, param: (LengthAndVector)): Unit = {
    model(id) = param
    itemIdsDescendingByLength += ((param._1, id))
  }
}
