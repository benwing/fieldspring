package opennlp.fieldspring
package tr.app

import util.io.localfh

/**
 * Concatenate two sets of WISTR features. Args are DIR1, DIR2 and DESTDIR.
 * This simply concatenates any files that exist in both DIR1 and DIR2.
 */
object ConcatenateWISTRFeatures extends App {
  val fh = localfh
  val dir1 = args(0)
  val dir2 = args(1)
  val destdir = args(2)
  val dir1_files = fh.list_files(dir1).map { path =>
    val (dir, tail) = fh.split_filename(path)
    tail
  }
  val dir2_files = fh.list_files(dir2).map { path =>
    val (dir, tail) = fh.split_filename(path)
    tail
  }
  fh.make_directories(destdir)
  def copy_file(src: String, dest: String, append: Boolean = false) {
    val outfile = fh.openw(dest, append = append)
    for (line <- fh.openr(src))
      outfile.println(line)
    outfile.close()
  }
  val files = (dir1_files ++ dir2_files).toSet.toSeq.sorted
  for (file <- files) {
    val src1 = fh.join_filename(dir1, file)
    val src2 = fh.join_filename(dir2, file)
    val dst = fh.join_filename(destdir, file)
    if (fh.exists(src1) && !fh.exists(src2))
      copy_file(src1, dst)
    else if (!fh.exists(src1) && fh.exists(src2))
      copy_file(src2, dst)
    else if (fh.exists(src1) && fh.exists(src2)) {
      copy_file(src1, dst)
      copy_file(src2, dst, append = true)
    }
  }
}
