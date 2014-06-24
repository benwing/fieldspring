package opennlp.fieldspring.tr.app

import java.io._

object SplitDevTest extends App {
  val dir = new File(args(0))
  // What fraction goes in "test". 3 means 1/3 in test, 5 means 1/5, etc.
  val testfrac =
    if (args.size > 1) args(1).toInt
    else 3

  val devDir = new File(dir.getCanonicalPath+"dev")
  val testDir = new File(dir.getCanonicalPath+"test")
  devDir.mkdir
  testDir.mkdir

  val files = dir.listFiles

  var i = 1
  for(file <- files) {
    if(i % testfrac == 0)
      file.renameTo(new File(testDir, file.getName))
    else
      file.renameTo(new File(devDir, file.getName))
    i += 1
  }
}
