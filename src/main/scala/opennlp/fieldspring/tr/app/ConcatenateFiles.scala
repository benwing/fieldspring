package opennlp.fieldspring
package tr.app

import util.io.localfh

/**
 * Concatenate files with the same names in each source directory. Called
 * as follows:
 *
 * ConcatenateFiles DESTDIR SRCDIR...
 *
 * for any number of source directories.
 */
object ConcatenateFiles extends App {
  val fh = localfh
  val destdir = args(0)
  val srcdirs = args.drop(1)
  fh.make_directories(destdir)
  def copy_file(src: String, dest: String, append: Boolean = false) {
    val outfile = fh.openw(dest, append = append)
    for (line <- fh.openr(src))
      outfile.println(line)
    outfile.close()
  }
  for (srcdir <- srcdirs; srcpath <- fh.list_files(srcdir)) {
    val (dir, tail) = fh.split_filename(srcpath)
    val destpath = fh.join_filename(destdir, tail)
    copy_file(srcpath, destpath, append = fh.exists(destpath))
  }
}
