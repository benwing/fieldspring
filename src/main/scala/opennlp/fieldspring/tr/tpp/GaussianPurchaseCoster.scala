package opennlp.fieldspring.tr.tpp

import opennlp.fieldspring.tr.topo._

class GaussianPurchaseCoster extends PurchaseCoster {

  val VARIANCE_KM = 161.0
  val variance = VARIANCE_KM / 6372.8

  def g(x:Double, y:Double) = GaussianUtil.g(x,y)

  //val maxHeight = g(0.0,0.0)

  val storedCosts = new scala.collection.mutable.HashMap[(Int, Int), Double] // (location.id, market.id) => distance
  def cost(l:Location, m:Market): Double = {
      val key = (l.getId, m.id)
      if(storedCosts.contains(key))
        storedCosts(key)
      else {
        val cost = 1.0-g(l.getRegion.distance(m.centroid)/variance, 0)///max
        //val cost = (maxHeight-g(l.getRegion.distance(m.centroid)/variance, 0))/maxHeight///max
        storedCosts.put(key, cost)
        cost
      }
  }

  def apply(m:Market, potLoc:PotentialLocation): Double = {
    cost(potLoc.loc, m)
  }
}
