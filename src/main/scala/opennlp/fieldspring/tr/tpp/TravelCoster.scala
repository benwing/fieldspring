package opennlp.fieldspring.tr.tpp

abstract class TravelCoster {

  def apply(m1:Market, m2:Market): Double
}
