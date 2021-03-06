package org.apache.flink.ml.parameter.server.matrix.factorization.factors

import scala.util.Random

class PseudoRandomFactorInitializer(numFactors: Int)
  extends FactorInitializer {
  override def nextFactor(id: Int): Array[Double] = {
    val random = new Random(id)
    Array.fill(numFactors)(random.nextDouble)
  }
}

case class PseudoRandomFactorInitializerDescriptor(numFactors: Int)
  extends FactorInitializerDescriptor {

  override def open(): FactorInitializer =
    new PseudoRandomFactorInitializer(numFactors)
}
