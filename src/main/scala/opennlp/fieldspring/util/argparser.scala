///////////////////////////////////////////////////////////////////////////////
//  argparser.scala
//
//  Copyright (C) 2011-2014 Ben Wing, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////

package opennlp.fieldspring
package util

import scala.util.control.Breaks._
import scala.collection.mutable

import org.clapper.argot._

/**
  This module implements an argument parser for Scala, which handles
  both options (e.g. --output-file foo.txt) and positional arguments.
  It is built on top of Argot and has an interface that is designed to
  be quite similar to the argument-parsing mechanisms in Python.

  The parser tries to be easy to use, and in particular to emulate the
  field-based method of accessing values used in Python.  This leads to
  the need to be very slightly tricky in the way that arguments are
  declared; see below.

  The basic features here that Argot doesn't have are:

  (1) Argument specification is simplified through the use of optional
      parameters to the argument-creation functions.
  (2) A simpler and easier-to-use interface is provided for accessing
      argument values given on the command line, so that the values can be
      accessed as simple field references (re-assignable if needed).
  (3) Default values can be given.
  (4) "Choice"-type arguments can be specified (only one of a fixed number
      of choices allowed).  This can include multiple aliases for a given
      choice.
  (5) The "flag" type is simplified to just handle boolean flags, which is
      the most common usage. (FIXME: Eventually perhaps we should consider
      also allowing more general Argot-type flags.)
  (6) The help text can be split across multiple lines using multi-line
      strings, and extra whitespace/carriage returns will be absorbed
      appropriately.  In addition, directives can be given, such as %default
      for the default value, %choices for the list of choices, %metavar
      for the meta-variable (see below), %prog for the name of the program,
      %% for a literal percent sign, etc.
  (7) A reasonable default value is provided for the "meta-variable"
      parameter for options (which specifies the type of the argument).
  (8) Conversion functions are easier to specify, since one function suffices
      for all types of arguments.
  (9) A conversion function is provided for type Boolean for handling
      valueful boolean options, where the value can be any of "yes, no,
      y, n, true, false, t, f, on, off".
  (10) There is no need to manually catch exceptions (e.g. from usage errors)
       if you don't want to.  By default, exceptions are caught
       automatically and printed out nicely, and then the program exits
       with return code 1.  You can turn this off if you want to handle
       exceptions yourself.

  In general, to parse arguments, you first create an object of type
  ArgParser (call it `ap`), and then add options to it by calling
  functions, typically:

  -- ap.option[T]() for a single-valued option of type T
  -- ap.multiOption[T]() for a multi-valued option of type T (i.e. the option
       can be specified multiple times on the command line, and all such
       values will be accumulated into a List)
  -- ap.flag() for a boolean flag
  -- ap.positional[T]() for a positional argument (coming after all options)
  -- ap.multiPositional[T]() for a multi-valued positional argument (i.e.
     eating up any remaining positional argument given)

  There are two styles for accessing the values of arguments specified on the
  command line.  One possibility is to simply declare arguments by calling the
  above functions, then parse a command line using `ap.parse()`, then retrieve
  values using `ap.get[T]()` to get the value of a particular argument.

  However, this style is not as convenient as we'd like, especially since
  the type must be specified.  In the original Python API, once the
  equivalent calls have been made to specify arguments and a command line
  parsed, the argument values can be directly retrieved from the ArgParser
  object as if they were fields; e.g. if an option `--outfile` were
  declared using a call like `ap.option[String]("outfile", ...)`, then
  after parsing, the value could simply be fetched using `ap.outfile`,
  and assignment to `ap.outfile` would be possible, e.g. if the value
  is to be defaulted from another argument.

  This functionality depends on the ability to dynamically intercept
  field references and assignments, which doesn't currently exist in
  Scala.  However, it is possible to achieve a near-equivalent.  It works
  like this:

  1) Functions like `ap.option[T]()` are set up so that the first time they
     are called for a given ArgParser object and argument, they will note
     the argument, and return the default value of this argument.  If called
     again after parsing, however, they will return the value specified in
     the command line (or the default if no value was specified). (If called
     again *before* parsing, they simply return the default value, as before.)

  2) A class, e.g. ProgParams, is created to hold the values returned from
     the command line.  This class typically looks like this:

     class ProgParams(ap: ArgParser) {
       var outfile = ap.option[String]("outfile", "o", ...)
       var verbose = ap.flag("verbose", "v", ...)
       ...
     }

  3) To parse a command line, we proceed as follows:

     a) Create an ArgParser object.
     b) Create an instance of ProgParams, passing in the ArgParser object.
     c) Call `parse()` on the ArgParser object, to parse a command line.
     d) Create *another* instance of ProgParams, passing in the *same*
        ArgParser object.
     e) Now, the argument values specified on the command line can be
        retrieved from this new instance of ProgParams simply using field
        accesses, and new values can likewise be set using field accesses.

  Note how this actually works.  When the first instance of ProgParams is
  created, the initialization of the variables causes the arguments to be
  specified on the ArgParser -- and the variables have the default values
  of the arguments.  When the second instance is created, after parsing, and
  given the *same* ArgParser object, the respective calls to "initialize"
  the arguments have no effect, but now return the values specified on the
  command line.  Because these are declared as `var`, they can be freely
  re-assigned.  Furthermore, because no actual reflection or any such thing
  is done, the above scheme will work completely fine if e.g. ProgParams
  subclasses another class that also declares some arguments (e.g. to
  abstract out common arguments for multiple applications).  In addition,
  there is no problem mixing and matching the scheme described here with the
  conceptually simpler scheme where argument values are retrieved using
  `ap.get[T]()`.

  Work being considered:

  (1) Perhaps most important: Allow grouping of options.  Groups would be
      kept together in the usage message, displayed under the group name.
  (2) Add constructor parameters to allow for specification of the other
      things allowed in Argot, e.g. pre-usage, post-usage, whether to sort
      arguments in the usage message or leave as-is.
  (3) Provide an option to control how meta-variable generation works.
      Normally, an unspecified meta-variable is derived from the
      canonical argument name in all uppercase (e.g. FOO or STRATEGY),
      but some people e.g. might instead want it derived from the argument
      type (e.g. NUM or STRING).  This would be controlled with a
      constructor parameter to ArgParser.
  (4) Add converters for other basic types, e.g. Float, Char, Byte, Short.
  (5) Allow for something similar to Argot's typed flags (and related
      generalizations).  I'd call it `ap.typedFlag[T]()` or something
      similar.  But rather than use Argot's interface of "on" and "off"
      flags, I'd prefer to follow the lead of Python's argparse, allowing
      the "destination" argument name to be specified independently of
      the argument name as it appears on the command line, so that
      multiple arguments could write to the same place.  I'd also add a
      "const" parameter that stores an arbitrary constant value if a
      flag is tripped, so that you could simulate the equivalent of a
      limited-choice option using multiple flag options.  In addition,
      I'd add an optional "action" parameter that is passed in the old
      and new values and returns the actual value to be stored; that
      way, incrementing/decrementing or whatever could be implemented.
      Note that I believe it's better to separate the conversion and
      action routines, unlike what Argot does -- that way, the action
      routine can work with properly-typed values and doesn't have to
      worry about how to convert them to/from strings.  This also makes
      it possible to supply action routines for all the various categories
      of arguments (e.g. flags, options, multi-options), while keeping
      the conversion routines simple -- the action routines necessarily
      need to be differently-typed at least for single vs.  multi-options,
      but the conversion routines shouldn't have to worry about this.
      In fact, to truly implement all the generality of Python's 'argparse'
      routine, we'd want expanded versions of option[], multiOption[],
      etc. that take both a source type (to which the raw values are
      initially converted) and a destination type (final type of the
      value stored), so that e.g. a multiOption can sum values into a
      single 'accumulator' destination argument, or a single String
      option can parse a classpath into a List of File objects, or
      whatever. (In fact, however, I think it's better to dispense
      with such complexity in the ArgParser and require instead that the
      calling routine deal with it on its own.  E.g. there's absolutely
      nothing preventing a calling routine using field-style argument
      values from declaring extra vars to hold destination values and
      then e.g. simply fetching the classpath value, parsing it and
      storing it, or fetching all values of a multiOption and summing
      them.  The minimal support for the Argot example of increment and
      decrement flags would be something like a call `ap.multiFlag`
      that accumulates a list of Boolean "true" values, one per
      invocation.  Then we just count the number of increment flags and
      number of decrement flags given.  If we cared about the relative
      way that these two flags were interleaved, we'd need a bit more
      support -- (1) a 'destination' argument to allow two options to
      store into the same place; (2) a typed `ap.multiFlag[T]`; (3)
      a 'const' argument to specify what value to store.  Then our
      destination gets a list of exactly which flags were invoked and
      in what order. On the other hand, it's easily arguable that no
      program should have such intricate option processing that requires
      this -- it's unlikely the user will have a good understanding
      of what these interleaved flags end up doing.
 */

package object argparser {
  /*

  NOTE: At one point, in place of the second scheme described above, there
  was a scheme involving reflection.  This didn't work as well, and ran
  into various problems.  One such problem is described here, because it
  shows some potential limitations/bugs in Scala.  In particular, in that
  scheme, calls to `ap.option[T]()` and similar were declared using `def`
  instead of `var`, and the first call to them was made using reflection.
  Underlyingly, all defs, vars and vals look like functions, and fields
  declared as `def` simply look like no-argument functions.  Because the
  return type can vary and generally is a simple type like Int or String,
  there was no way to reliably recognize defs of this sort from other
  variables, functions, etc. in the object.  To make this recognition
  reliable, I tried wrapping the return value in some other object, with
  bidirectional implicit conversions to/from the wrapped value, something
  like this:

  class ArgWrap[T](vall: T) extends ArgAny[T] {
    def value = vall
    def specified = true
  }

  implicit def extractValue[T](arg: ArgAny[T]): T = arg.value

  implicit def wrapValue[T](vall: T): ArgAny[T] = new ArgWrap(vall)

  Unfortunately, this didn't work, for somewhat non-obvious reasons.
  Specifically, the problems were:

  (1) Type unification between wrapped and unwrapped values fails.  This is
      a problem e.g. if I try to access a field value in an if-then-else
      statements like this, I run into problems:

      val files =
        if (use_val)
          Params.files
        else
          Seq[String]()

      This unifies to AnyRef, not Seq[String], even if Params.files wraps
      a Seq[String].

  (2) Calls to methods on the wrapped values (e.g. strings) can fail in
      weird ways.  For example, the following fails:

      def words = ap.option[String]("words")

      ...

      val split_words = ap.words.split(',')

      However, it doesn't fail if the argument passed in is a string rather
      than a character.  In this case, if I have a string, I *can* call
      split with a character as an argument - perhaps this fails in the case
      of an implicit conversion because there is a split() implemented on
      java.lang.String that takes only strings, whereas split() that takes
      a character is stored in StringOps, which itself is handled using an
      implicit conversion.
   */

  /**
   * Implicit conversion function for Ints.  Automatically selected
   * for Int-type arguments.
   */
  implicit def convertInt(rawval: String, name: String, ap: ArgParser) = {
    val canonval = rawval.replace("_","").replace(",","")
    try { canonval.toInt }
    catch {
      case e: NumberFormatException =>
        throw new ArgParserConversionException(
          """Argument '%s': Cannot convert value '%s' to an integer"""
          format (name, rawval))
    }
  }

  /**
   * Implicit conversion function for Doubles.  Automatically selected
   * for Double-type arguments.
   */
  implicit def convertDouble(rawval: String, name: String, ap: ArgParser) = {
    val canonval = rawval.replace("_","").replace(",","")
    try { canonval.toDouble }
    catch {
      case e: NumberFormatException =>
        throw new ArgParserConversionException(
          """Argument '%s': Cannot convert value '%s' to a floating-point number"""
          format (name, rawval))
    }
  }

  /**
   * Implicit conversion function for Strings.  Automatically selected
   * for String-type arguments.
   */
  implicit def convertString(rawval: String, name: String, ap: ArgParser) = {
    rawval
  }

  /**
   * Implicit conversion function for Boolean arguments, used for options
   * that take a value (rather than flags).
   */
  implicit def convertBoolean(rawval: String, name: String, ap: ArgParser) = {
    rawval.toLowerCase match {
      case "yes" => true
      case "no" => false
      case "y" => true
      case "n" => false
      case "true" => true
      case "false" => false
      case "t" => true
      case "f" => false
      case "on" => true
      case "off" => false
      case _ => throw new ArgParserConversionException(
          ("""Argument '%s': Cannot convert value '%s' to a boolean.  """ +
           """Recognized values (case-insensitive) are """ +
           """yes, no, y, n, true, false, t, f, on, off""") format
           (name, rawval))
    }
  }

  /**
   * Check that the value is &lt; a given integer. Used with argument `must`.
   */
  def be_<(num: Int) =
    Must[Int]( { x => x < num }, "value %%s must be < %s" format num)
  /**
   * Check that the value is &lt; a given double. Used with argument `must`.
   */
  def be_<(num: Double) =
    Must[Double]( { x => x < num }, "value %%s must be < %s" format num)
  /**
   * Check that the value is &gt; a given integer. Used with argument `must`.
   */
  def be_>(num: Int) =
    Must[Int]( { x => x > num }, "value %%s must be > %s" format num)
  /**
   * Check that the value is &gt; a given double. Used with argument `must`.
   */
  def be_>(num: Double) =
    Must[Double]( { x => x > num }, "value %%s must be > %s" format num)
  /**
   * Check that the value is &lt;= a given integer. Used with argument `must`.
   */
  def be_<=(num: Int) =
    Must[Int]( { x => x <= num }, "value %%s must be <= %s" format num)
  /**
   * Check that the value is &lt;= a given double. Used with argument `must`.
   */
  def be_<=(num: Double) =
    Must[Double]( { x => x <= num }, "value %%s must be <= %s" format num)
  /**
   * Check that the value is &gt;= a given integer. Used with argument `must`.
   */
  def be_>=(num: Int) =
    Must[Int]( { x => x >= num }, "value %%s must be >= %s" format num)
  /**
   * Check that the value is &gt;= a given double. Used with argument `must`.
   */
  def be_>=(num: Double) =
    Must[Double]( { x => x >= num }, "value %%s must be >= %s" format num)
  /**
   * Check that the value is a given value. Used with argument `must`.
   */
  def be_==[T](value: T) =
    Must[T]( { x => x == value}, "value %%s must be = %s" format value)
  /**
   * Check that the value is not a given value. Used with argument `must`.
   */
  def be_!=[T](value: T) =
    Must[T]( { x => x != value}, "value %%s must not be = %s" format value)
  /**
   * Check that the value is within a given integer range. Used with
   * argument `must`.
   */
  def be_within(lower: Int, upper: Int) =
    Must[Int]( { x => x >= lower && x <= upper },
      "value %%s must be within range [%s, %s]" format (lower, upper))
  /**
   * Check that the value is within a given double range. Used with
   * argument `must`.
   */
  def be_within(lower: Double, upper: Double) =
    Must[Double]( { x => x >= lower && x <= upper },
      "value %%s must be within range [%s, %s]" format (lower, upper))
  /**
   * Check that the value is specified. Used with argument `must`.
   */
  def be_specified[T] =
    Must[T]( { x => x != null.asInstanceOf[T] },
      "value must be specified")
  /**
   * Check that the value satisfies all of the given restrictions.
   * Used with argument `must`.
   */
  def be_and[T](musts: Must[T]*) =
    Must[T]( { x => musts.forall { y => y.fn(x) } },
      musts.map { y =>
        val errmess = y.errmess
        if (errmess == null) "unknown restriction"
        else errmess
      }.mkString(" and "))
  /**
   * Check that the value satisfies at least one of the given restrictions.
   * Used with argument `must`.
   */
  def be_or[T](musts: Must[T]*) =
    Must[T]( { x => musts.exists { y => y.fn(x) } },
      musts.map { y =>
        val errmess = y.errmess
        if (errmess == null) "unknown restriction"
        else errmess
      }.mkString(" or "))

  /**
   * Check that the value does not satisfy of the given restriction.
   * Used with argument `must`.
   */
  def be_not[T](must: Must[T]) =
    Must[T]( { x => !must.fn(x) },
      if (must.errmess == null) null else
      "must not be the case: " + must.errmess)

  /**
   * Check that the value is one of the given choices.
   * Used with argument `must`.
   *
   * FIXME: Implement `aliasedChoices` as well.
   */
  def choices[T](values: T*) =
    Must[T]( { x => values contains x },
      "choice '%%s' not one of the recognized choices: %s" format
      (values mkString ","))

  /**
   * Execute the given body and catch parser errors. When they occur,
   * output the message and exit with exit code 1, rather than outputting
   * a stack trace.
   */
  def catch_parser_errors[T](body: => T): T = {
    try {
      body
    } catch {
      case e: ArgParserException => {
        System.err.println(e.message)
        System.exit(1)
        ??? // Should never get here!
      }
    }
  }
}

package argparser {
  /* Class specifying a restriction on a possible value and possible
   * transformation of the value. Applying the argument name and the
   * converted value will either return a possibly transformed value
   * or throw an error if the value does not pass the restriction.
   *
   * @param fn Function specifying a restriction. If null, no restriction.
   * @param errmess Error message to be displayed when restriction fails,
   *   optionally containing a %s in it indicating where to display the
   *   value.
   * @param transform Function to transform the value. If null,
   *   no transformation.
   */
  case class Must[T](fn: T => Boolean, errmess: String = null,
      transform: (String, T) => T = null) {
    def apply(canon_name: String, converted: T): T = {
      if (fn != null && !fn(converted)) {
        val the_errmess =
          if (errmess != null) errmess
          else "value '%s' not one of the allowed values"
        val msg =
          if (the_errmess contains "%s") the_errmess format converted
          else the_errmess
        throw new ArgParserRestrictionException(
          "Argument '%s': %s" format (canon_name, msg))
      }
      if (transform != null)
        transform(canon_name, converted)
      else
        converted
    }
  }

  /**
   * Superclass of all exceptions related to `argparser`.  These exceptions
   * are generally thrown during argument parsing.  Normally, the exceptions
   * are automatically caught, their message displayed, and then the
   * program exited with code 1, indicating a problem.  However, this
   * behavior can be suppressed by setting the constructor parameter
   * `catchErrors` on `ArgParser` to false.  In such a case, the exceptions
   * will be propagated to the caller, which should catch them and handle
   * appropriately; otherwise, the program will be terminated with a stack
   * trace.
   *
   * @param message Message of the exception
   * @param cause If not None, an exception, used for exception chaining
   *   (when one exception is caught, wrapped in another exception and
   *   rethrown)
   */
  class ArgParserException(val message: String,
    val cause: Option[Throwable] = None) extends Exception(message) {
    if (cause != None)
      initCause(cause.get)

    /**
     * Alternate constructor.
     *
     * @param message  exception message
     */
    def this(msg: String) = this(msg, None)

    /**
     * Alternate constructor.
     *
     * @param message  exception message
     * @param cause    wrapped, or nested, exception
     */
    def this(msg: String, cause: Throwable) = this(msg, Some(cause))
  }

  /**
   * Thrown to indicate usage errors.
   *
   * @param message fully fleshed-out usage string.
   * @param cause exception, if propagating an exception
   */
  class ArgParserUsageException(
    message: String,
    cause: Option[Throwable] = None
  ) extends ArgParserException(message, cause)

  /**
   * Thrown to indicate that ArgParser could not convert a command line
   * argument to the desired type.
   *
   * @param message exception message
   * @param cause exception, if propagating an exception
   */
  class ArgParserConversionException(
    message: String,
    cause: Option[Throwable] = None
  ) extends ArgParserException(message, cause)

  /**
   * Thrown to indicate that a command line argument failed to be one of
   * the allowed values.
   *
   * @param message exception message
   * @param cause exception, if propagating an exception
   */
  class ArgParserRestrictionException(
    message: String,
    cause: Option[Throwable] = None
  ) extends ArgParserException(message, cause)

  /**
   * Thrown to indicate that ArgParser encountered a problem in the caller's
   * argument specification, or something else indicating invalid coding.
   * This indicates a bug in the caller's code.  These exceptions are not
   * automatically caught.
   *
   * @param message  exception message
   */
  class ArgParserCodingError(message: String,
    cause: Option[Throwable] = None
  ) extends ArgParserException("(CALLER BUG) " + message, cause)

  /**
   * Thrown to indicate that ArgParser encountered a problem that should
   * never occur under any circumstances, indicating a bug in the ArgParser
   * code itself.  These exceptions are not automatically caught.
   *
   * @param message  exception message
   */
  class ArgParserInternalError(message: String,
    cause: Option[Throwable] = None
  ) extends ArgParserException("(INTERNAL BUG) " + message, cause)

  /* Some static functions related to ArgParser; all are for internal use */
  protected object ArgParser {
    // Given a list of aliases for an argument, return the canonical one
    // (first one that's more than a single letter).
    def canonName(name: Seq[String]): String = {
      assert(name.length > 0)
      for (n <- name) {
        if (n.length > 1) return n
      }
      return name(0)
    }

    // Compute the metavar for an argument.  If the metavar has already
    // been given, use it; else, use the upper case version of the
    // canonical name of the argument.
    def computeMetavar(metavar: String, name: Seq[String]) = {
      if (metavar != null) metavar
      else canonName(name).toUpperCase
    }

    // Return a sequence of all the given strings that aren't null.
    def nonNullVals(val1: String, val2: String, val3: String, val4: String,
      val5: String, val6: String, val7: String, val8: String,
      val9: String) = {
      val retval =
        Seq(val1, val2, val3, val4, val5, val6, val7, val8, val9) filter
          (_ != null)
      if (retval.length == 0)
        throw new ArgParserCodingError(
          "Need to specify at least one name for each argument")
      retval
    }

    // Combine `choices` and `aliasedChoices` into a larger list of the
    // format of `aliasedChoices`.  Note that before calling this, a special
    // check must be done for the case where `choices` and `aliasedChoices`
    // are both null, which includes that no limited-choice restrictions
    // apply at all (and is actually the most common situation).
    def canonicalizeChoicesAliases[T](choices: Seq[T],
        aliasedChoices: Seq[Seq[T]]) = {
      val newchoices = if (choices != null) choices else Seq[T]()
      val newaliased =
        if (aliasedChoices != null) aliasedChoices else Seq[Seq[T]]()
      for (spellings <- newaliased) {
        if (spellings.length == 0)
          throw new ArgParserCodingError(
            "Zero-length list of spellings not allowed in `aliasedChoices`:\n%s"
            format newaliased)
      }
      newchoices.map(x => Seq(x)) ++ newaliased
    }

    // Convert a list of choices in the format of `aliasedChoices`
    // (a sequence of sequences, first item is the canonical spelling)
    // into a mapping that canonicalizes choices.
    def getCanonMap[T](aliasedChoices: Seq[Seq[T]]) = {
      (for {spellings <- aliasedChoices
            canon = spellings.head
            spelling <- spellings}
         yield (spelling, canon)).toMap
    }

    // Return a human-readable list of all choices, based on the specifications
    // of `choices` and `aliasedChoices`. If 'includeAliases' is true, include
    // the aliases in the list of choices, in parens after the canonical name.
    def choicesList[T](choices: Seq[T], aliasedChoices: Seq[Seq[T]],
        includeAliases: Boolean) = {
      val fullaliased =
        canonicalizeChoicesAliases(choices, aliasedChoices)
      if (!includeAliases)
        fullaliased.map(_.head) mkString ", "
      else
        (
          for { spellings <- fullaliased
                canon = spellings.head
                altspellings = spellings.tail
              }
          yield {
            if (altspellings.length > 0)
              "%s (%s)" format (canon, altspellings mkString "/")
            else canon.toString
          }
        ) mkString ", "
    }

    // Check that the given value passes any restrictions imposed by
    // `must`, `choices` and/or `aliasedChoices`.
    // If not, throw an exception.
    def checkRestriction[T](canon_name: String, converted: T,
        must: Must[T], choices: Seq[T], aliasedChoices: Seq[Seq[T]]) = {
      val new_converted =
        if (must == null) converted else must(canon_name, converted)
      if (choices == null && aliasedChoices == null) converted
      else {
        val fullaliased =
          canonicalizeChoicesAliases(choices, aliasedChoices)
        val canonmap = getCanonMap(fullaliased)
        if (canonmap contains converted)
          canonmap(converted)
        else
          throw new ArgParserRestrictionException(
            "Argument '%s': choice '%s' not one of the recognized choices: %s"
            format (canon_name, converted, choicesList(choices, aliasedChoices,
              includeAliases = true)))
      }
    }
  }

  /**
   * Base class of all argument-wrapping classes.  These are used to
   * wrap the appropriate argument-category class from Argot, and return
   * values by querying Argot for the value, returning the default value
   * if Argot doesn't have a value recorded.
   *
   * NOTE that these classes are not meant to leak out to the user.  They
   * should be considered implementation detail only and subject to change.
   *
   * @param parser ArgParser for which this argument exists.
   * @param name Name of the argument.
   * @param default Default value of the argument, used when the argument
   *   wasn't specified on the command line.
   * @tparam T Type of the argument (e.g. Int, Double, String, Boolean).
   */

  abstract protected class ArgAny[T](
    val parser: ArgParser,
    val name: String,
    val default: T,
    checkres: T => T
  ) {
    /**
     * Return the value of the argument, if specified; else, the default
     * value. */
    def value = {
      if (overridden)
        overriddenValue
      else if (specified)
        wrappedValue
      else
        checkres(default)
    }

    def setValue(newval: T) {
      overriddenValue = newval
      overridden = true
    }

    /**
     * When dereferenced as a function, also return the value.
     */
    def apply() = value

    /**
     * Whether the argument's value was specified.  If not, the default
     * value applies.
     */
    def specified: Boolean

    /**
     * Clear out any stored values so that future queries return the default.
     */
    def clear() {
      clearWrapped()
      overridden = false
    }

    /**
     * Return the value of the underlying Argot object, assuming it exists
     * (possibly error thrown if not).
     */
    protected def wrappedValue: T

    /**
     * Clear away the wrapped value.
     */
    protected def clearWrapped()

    /**
     * Value if the user explicitly set a value.
     */
    protected var overriddenValue: T = _

    /**
     * Whether the user explicit set a value.
     */
    protected var overridden: Boolean = false

  }

  /**
   * Class for wrapping simple Boolean flags.
   *
   * @param parser ArgParser for which this argument exists.
   * @param name Name of the argument.
   */

  protected class ArgFlag(
    parser: ArgParser,
    name: String
  ) extends ArgAny[Boolean](parser, name, default = false,
      checkres = { x: Boolean => x }) {
    var wrap: FlagOption[Boolean] = null
    def wrappedValue = wrap.value.get
    def specified = (wrap != null && wrap.value != None)
    def clearWrapped() { if (wrap != null) wrap.reset() }
  }

  /**
   * Class for wrapping a single (non-multi) argument (either option or
   * positional param).
   *
   * @param parser ArgParser for which this argument exists.
   * @param name Name of the argument.
   * @param default Default value of the argument, used when the argument
   *   wasn't specified on the command line.
   * @param is_positional Whether this is a positional argument rather than
   *   option (default false).
   * @tparam T Type of the argument (e.g. Int, Double, String, Boolean).
   */

  protected class ArgSingle[T](
    parser: ArgParser,
    name: String,
    default: T,
    checkres: T => T,
    val is_positional: Boolean = false
  ) extends ArgAny[T](parser, name, default, checkres) {
    var wrap: SingleValueArg[T] = null
    def wrappedValue = wrap.value.get
    def specified = (wrap != null && wrap.value != None)
    def clearWrapped() { if (wrap != null) wrap.reset() }
  }

  /**
   * Class for wrapping a multi argument (either option or positional param).
   *
   * @param parser ArgParser for which this argument exists.
   * @param name Name of the argument.
   * @param default Default value of the argument, used when the argument
   *   wasn't specified on the command line even once.
   * @param is_positional Whether this is a positional argument rather than
   *   option (default false).
   * @tparam T Type of the argument (e.g. Int, Double, String, Boolean).
   */

  protected class ArgMulti[T](
    parser: ArgParser,
    name: String,
    default: Seq[T],
    checkres: T => T,
    val is_positional: Boolean = false
  ) extends ArgAny[Seq[T]](parser, name, default,
      { x => x.map(checkres) }) {
    var wrap: MultiValueArg[T] = null
    val wrapSingle = new ArgSingle[T](parser, name, null.asInstanceOf[T],
      checkres)
    def wrappedValue = wrap.value
    def specified = (wrap != null && wrap.value.length > 0)
    def clearWrapped() { if (wrap != null) wrap.reset() }
  }

  /**
   * Main class for parsing arguments from a command line.
   *
   * @param prog Name of program being run, for the usage message.
   * @param description Text describing the operation of the program.  It is
   *   placed between the line "Usage: ..." and the text describing the
   *   options and positional arguments; hence, it should not include either
   *   of these, just a description.
   * @param preUsage Optional text placed before the usage message (e.g.
   *   a copyright and/or version string).
   * @param postUsage Optional text placed after the usage message.
   * @param return_defaults If true, field values in field-based value
   *  access always return the default value, even after parsing.
   */
  class ArgParser(prog: String,
      description: String = "",
      preUsage: String = "",
      postUsage: String = "",
      return_defaults: Boolean = false) {
    import ArgParser._
    import ArgotConverters._
    /* The underlying ArgotParser object. */
    protected val argot = new ArgotParser(prog,
      description = if (description.length > 0) Some(description) else None,
      preUsage = if (preUsage.length > 0) Some(preUsage) else None,
      postUsage = if (postUsage.length > 0) Some(postUsage) else None)
    /* A map from the argument's canonical name to the subclass of ArgAny
       describing the argument and holding its value.  The canonical name
       of options comes from the first non-single-letter name.  The
       canonical name of positional arguments is simply the name of the
       argument.  Iteration over the map yields keys in the order they
       were added rather than random. */
    protected val argmap = mutable.LinkedHashMap[String, ArgAny[_]]()
    /* The type of each argument.  For multi options and multi positional
       arguments this will be of type Seq.  Because of type erasure, the
       type of sequence must be stored separately, using argtype_multi. */
    protected val argtype = mutable.Map[String, Class[_]]()
    /* For multi arguments, the type of each individual argument. */
    protected val argtype_multi = mutable.Map[String, Class[_]]()
    /* Set specifying arguments that are positional arguments. */
    protected val argpositional = mutable.Set[String]()
    /* Set specifying arguments that are flag options. */
    protected val argflag = mutable.Set[String]()
    /* Map from argument aliases to canonical argument name. Note that
     * currently this isn't actually used when looking up an argument name;
     * that lookup is handled internally to Argot, which has its own
     * tables. */
    protected val arg_to_canon = mutable.Map[String, String]()

    protected var parsed = false

    /* NOTE NOTE NOTE: Currently we don't provide any programmatic way of
       accessing the ArgAny-subclass object by name.  This is probably
       a good thing -- these objects can be viewed as internal
    */

    /**
     * Return whether we've already parsed the command line.
     */
    def isParsed = parsed

    /**
     * Return whether variables holding the return value of parameters
     * hold the parsed values. Otherwise they hold the default values,
     * which happens either when we haven't parsed the command line or
     * when class parameter `return_defaults` was specified.
     */
    def parsedValues = isParsed && !return_defaults

    /**
     * Return the canonical name of an argument. If the name is already
     * canonical, the same value will be returned. Return value is an
     * `Option`; if the argument name doesn't exist, `None` will be returned.
     *
     * @param arg The name of the argument.
     */
    def argToCanon(arg: String): Option[String] = arg_to_canon.get(arg)

    // Look the argument up in `argmap`, converting to canonical as needed.
    protected def get_arg(arg: String) = argmap(arg_to_canon(arg))

    /**
     * Return the value of an argument, or the default if not specified.
     *
     * @param arg The name of the argument.
     * @return The value, of type Any.  It must be cast to the appropriate
     *   type.
     * @see #get[T]
     */
    def apply(arg: String) = get_arg(arg).value

    /**
     * Return the value of an argument, or the default if not specified.
     *
     * @param arg The name of the argument.
     * @tparam T The type of the argument, which must match the type given
     *   in its definition
     *
     * @return The value, of type T.
     */
    def get[T](arg: String) = get_arg(arg).asInstanceOf[ArgAny[T]].value

    /**
     * Explicitly set the value of an argument.
     *
     * @param arg The name of the argument.
     * @param value The new value of the argument.
     * @tparam T The type of the argument, which must match the type given
     *   in its definition
     *
     * @return The value, of type T.
     */
    def set[T](arg: String, value: T) {
      get_arg(arg).asInstanceOf[ArgAny[T]].setValue(value)
    }

    /**
     * Return the default value of an argument.
     *
     * @param arg The name of the argument.
     * @tparam T The type of the argument, which must match the type given
     *   in its definition
     *
     * @return The value, of type T.
     */
    def defaultValue[T](arg: String) =
      get_arg(arg).asInstanceOf[ArgAny[T]].default

    /**
     * Return whether an argument (either option or positional argument)
     * exists with the given name.
     */
    def exists(arg: String) = arg_to_canon contains arg

    /**
     * Return whether an argument (either option or positional argument)
     * exists with the given canonical name.
     */
    def existsCanon(arg: String) = argmap contains arg

    /**
     * Return whether an argument exists with the given name.
     */
    def isOption(arg: String) = exists(arg) && !isPositional(arg)

    /**
     * Return whether a positional argument exists with the given name.
     */
    def isPositional(arg: String) =
      argToCanon(arg).map(argpositional contains _) getOrElse false

    /**
     * Return whether a flag option exists with the given name.
     */
    def isFlag(arg: String) =
      argToCanon(arg).map(argflag contains _) getOrElse false

    /**
     * Return whether a multi argument (either option or positional argument)
     * exists with the given canonical name.
     */
    def isMulti(arg: String) =
      argToCanon(arg).map(argtype_multi contains _) getOrElse false

    /**
     * Return whether the given argument's value was specified.  If not,
     * fetching the argument's value returns its default value instead.
     */
    def specified(arg: String) = get_arg(arg).specified

    /**
     * Return the type of the given argument.  For multi arguments, the
     * type will be Seq, and the type of the individual arguments can only
     * be retrieved using `getMultiType`, due to type erasure.
     */
    def getType(arg: String) = argtype(arg_to_canon(arg))

    /**
     * Return the type of an individual argument value of a multi argument.
     * The actual type of the multi argument is a Seq of the returned type.
     */
    def getMultiType(arg: String) = argtype_multi(arg_to_canon(arg))

    /**
     * Return an Iterable over the canonical names of all defined arguments.
     * Values of the arguments can be retrieved using `apply` or `get[T]`.
     * Properties of the arguments can be retrieved using `getType`,
     * `specified`, `defaultValue`, `isFlag`, etc.
     */
    def argNames: Iterable[String] = {
      for ((name, argobj) <- argmap) yield name
    }

    /**
     * Return an Iterable over pairs of canonically-named arguments and values
     * (of type Any). The values need to be cast as appropriate.
     *
     * @see #argNames, #get[T], #apply
     */
    def argValues: Iterable[(String, Any)] = {
      for ((name, argobj) <- argmap) yield (name, argobj.value)
    }

    /**
     * Return an Iterable over pairs of canonically-named arguments and values
     * (of type Any), only including arguments whose values were specified on
     * the command line. The values need to be cast as appropriate.
     *
     * @see #argNames, #argValues, #get[T], #apply
     */
    def nonDefaultArgValues: Iterable[(String, Any)] = {
      for ((name, argobj) <- argmap if argobj.specified)
        yield (name, argobj.value)
    }

    /**
     * Underlying function to implement the handling of all different types
     * of arguments. Normally this will be called twice for each argument,
     * once before and once after parsing. When before parsing, it records
     * the argument and its properties. When after parsing, it returns the
     * value of the argument as parsed from the command line.
     *
     * @tparam U Type of the argument. The variable holding an argument's
     *   value will always have this type.
     * @tparam T Type of a single argument value. This will be different from
     *   `U` in the case of multi-arguments and arguments with parameters
     *   (in such case, `U` will consist of a tuple `(T, String)` or a
     *   sequence of such tuples). The elements in `choices` are of type `T`.
     *
     * @param Names of the argument.
     * @param default Default value of argument.
     * @param metavar User-visible argument type, in usage string. See
     *   `option` for more information.
     * @param must Restriction on possible values for this option, as an
     *    object of type `Must`.
     * @param choices Set of allowed choices, when an argument allows only
     *   a limited set of choices.
     * @param aliasedChoices List of allowed aliases for the choices specified
     *   in `choices`.
     * @param help Help string, to be displayed in the usage message.
     * @param create_underlying Function to create the underlying object
     *   (of a subclass of `ArgAny`) that wraps the argument. The function
     *   arguments are the canonicalized name, metavar and help. The
     *   canonicalized help has %-sequences subsituted appropriately; the
     *   canonical name is the first non-single-letter name listed; the
     *   canonical metavar is computed from the canonical name, in all-caps,
     *   if not specified.
     * @param is_multi Whether this is a multi-argument (allowing the argument
     *   to occur multiple times).
     * @param is_positional Whether this is a positional argument rather than
     *   an option.
     * @param is_flag Whether this is a flag (a Boolean option with no value
     *   specified).
     */
    protected def handle_argument[T : Manifest, U : Manifest](
      name: Seq[String],
      default: U,
      metavar: String,
      must: Must[T],
      choices: Seq[T],
      aliasedChoices: Seq[Seq[T]],
      help: String,
      create_underlying: (String, String, String) => ArgAny[U],
      is_multi: Boolean = false,
      is_positional: Boolean = false,
      is_flag: Boolean = false
    ) = {
      val canon = canonName(name)
      if (return_defaults)
        default
      else if (parsed) {
        if (argmap contains canon)
          argmap(canon).asInstanceOf[ArgAny[U]].value
        else
          throw new ArgParserCodingError("Can't define new arguments after parsing")
      } else {
        val canon_metavar = computeMetavar(metavar, name)
        val helpsplit = """(%%|%default|%choices|%allchoices|%metavar|%prog|%|[^%]+)""".r.findAllIn(
          help.replaceAll("""\s+""", " "))
        val canon_help =
          (for (s <- helpsplit) yield {
            s match {
              case "%default" => default.toString
              case "%choices" => choicesList(choices, aliasedChoices,
                includeAliases = false)
              case "%allchoices" => choicesList(choices, aliasedChoices,
                includeAliases = true)
              case "%metavar" => canon_metavar
              case "%%" => "%"
              case "%prog" => this.prog
              case _ => s
            }
          }) mkString ""
        val underobj = create_underlying(canon, canon_metavar, canon_help)
        for (nam <- name) {
          if (arg_to_canon contains nam)
            throw new ArgParserCodingError("Attempt to redefine existing argument '%s'" format nam)
          arg_to_canon(nam) = canon
        }
        argmap(canon) = underobj
        argtype(canon) = manifest[U].runtimeClass
        if (is_multi)
          argtype_multi(canon) = manifest[T].runtimeClass
        if (is_positional)
          argpositional += canon
        if (is_flag)
          argflag += canon
        default
      }
    }

    protected def argot_converter[T](
        is_multi: Boolean, convert: (String, String, ArgParser) => T,
        canon_name: String, checkres: T => T) = {
      (rawval: String, argop: CommandLineArgument[T]) => {
        val converted = convert(rawval, canon_name, this)
        checkres(converted)
      }
    }

    protected def argot_converter_with_params[T](
        is_multi: Boolean, convert: (String, String, ArgParser) => T,
        canon_name: String, checkres: ((T, String)) => (T, String)) = {
      (rawval: String, argop: CommandLineArgument[(T, String)]) => {
        val (raw, params) = rawval span (_ != ':')
        val converted = (convert(raw, canon_name, this), params)
        checkres(converted)
      }
    }

    def optionSeq[T](name: Seq[String],
      default: T = null.asInstanceOf[T],
      metavar: String = null,
      must: Must[T] = null,
      choices: Seq[T] = null,
      aliasedChoices: Seq[Seq[T]] = null,
      help: String = "")
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      def create_underlying(canon_name: String, canon_metavar: String,
          canon_help: String) = {
        val checkres = { x: T => checkRestriction(canon_name, x,
          must, choices, aliasedChoices) }
        val arg = new ArgSingle(this, canon_name, default, checkres)
        arg.wrap =
          (argot.option[T](name.toList, canon_metavar, canon_help)
           (argot_converter(is_multi = false, convert, canon_name,
             checkres)))
        arg
      }
      handle_argument[T,T](name, default, metavar, must,
        choices, aliasedChoices, help, create_underlying _)
    }

    def optionSeqWithParams[T](name: Seq[String],
      default: (T, String) = (null.asInstanceOf[T], ""),
      metavar: String = null,
      must: Must[T] = null,
      choices: Seq[T] = null,
      aliasedChoices: Seq[Seq[T]] = null,
      help: String = "")
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      def create_underlying(canon_name: String, canon_metavar: String,
          canon_help: String) = {
        val checkres = { x: ((T, String)) =>
          (checkRestriction(canon_name, x._1, must, choices, aliasedChoices),
           x._2) }
        val arg = new ArgSingle(this, canon_name, default, checkres)
        arg.wrap =
          (argot.option[(T, String)](name.toList, canon_metavar, canon_help)
           (argot_converter_with_params(is_multi = false, convert, canon_name,
             checkres)))
        arg
      }
      handle_argument[T,(T, String)](name, default, metavar, must,
        choices, aliasedChoices, help, create_underlying _)
    }

    /**
     * Define a single-valued option of type T.  Various standard types
     * are recognized, e.g. String, Int, Double. (This is handled through
     * the implicit `convert` argument.) Up to nine aliases for the
     * option can be specified.  Single-letter aliases are specified using
     * a single dash, whereas longer aliases generally use two dashes.
     * The "canonical" name of the option is the first non-single-letter
     * alias given.
     *
     * @param name1
     * @param name2
     * @param name3
     * @param name4
     * @param name5
     * @param name6
     * @param name7
     * @param name8
     * @param name9
     *    Up to nine aliases for the option; see above.
     *
     * @param default Default value, if option not specified; if not given,
     *    it will end up as 0, 0.0 or false for value types, null for
     *    reference types.
     * @param metavar "Type" of the option, as listed in the usage string.
     *    This is so that the relevant portion of the usage string will say
     *    e.g. "--counts-file FILE     File containing word counts." (The
     *    value of `metavar` would be "FILE".) If not given, automatically
     *    computed from the canonical option name by capitalizing it.
     * @param must Restriction on possible values for this option. This is
     *    a tuple of a function that must evaluate to true on the value
     *    and an error message to display otherwise, with a %s in it
     *    indicating where to display the value. There are a number of
     *    predefined functions to use, e.g. `be_&lt;`, `be_within`, etc.
     * @param choices List of possible choices for this option.  If specified,
     *    it should be a sequence of possible choices that will be allowed,
     *    and only the choices that are either in this list of specified via
     *    `aliasedChoices` will be allowed.  If neither `choices` nor
     *    `aliasedChoices` is given, all values will be allowed.
     * @param aliasedChoices List of possible choices for this option,
     *    including alternative spellings (aliases).  If specified, it should
     *    be a sequence of sequences, each of which specifies the possible
     *    alternative spellings for a given choice and where the first listed
     *    spelling is considered the "canonical" one.  All choices that
     *    consist of any given spelling will be allowed, but any non-canonical
     *    spellings will be replaced by equivalent canonical spellings.
     *    For example, the choices of "dev", "devel" and "development" may
     *    all mean the same thing; regardless of how the user spells this
     *    choice, the same value will be passed to the program (whichever
     *    spelling comes first).  Note that the value of `choices` lists
     *    additional choices, which are equivalent to choices listed in
     *    `aliasedChoices` without any alternative spellings.  If both
     *    `choices` and `aliasedChoices` are omitted, all values will be
     *    allowed.
     * @param help Help string for the option, shown in the usage string.
     * @param convert Function to convert the raw option (a string) into
     *    a value of type `T`.  The second and third parameters specify
     *    the name of the argument whose value is being converted, and the
     *    ArgParser object that the argument is defined on.  Under normal
     *    circumstances, these parameters should not affect the result of
     *    the conversion function.  For standard types, no conversion
     *    function needs to be specified, as the correct conversion function
     *    will be located automatically through Scala's 'implicit' mechanism.
     * @tparam T The type of the option.  For non-standard types, a
     *    converter must explicitly be given. (The standard types recognized
     *    are currently Int, Double, Boolean and String.)
     *
     * @return If class parameter `return_defaults` is true or if parsing
     *    has not yet happened, the default value.  Otherwise, the value of
     *    the parameter.
     */
    def option[T](
      name1: String, name2: String = null, name3: String = null,
      name4: String = null, name5: String = null, name6: String = null,
      name7: String = null, name8: String = null, name9: String = null,
      default: T = null.asInstanceOf[T],
      metavar: String = null,
      must: Must[T] = null,
      choices: Seq[T] = null,
      aliasedChoices: Seq[Seq[T]] = null,
      help: String = "")
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      optionSeq[T](nonNullVals(name1, name2, name3, name4, name5, name6,
        name7, name8, name9),
        metavar = metavar, default = default, must = must,
        choices = choices, aliasedChoices = aliasedChoices, help = help
      )(convert, m)
    }

    /**
     * Define a single-valued option of type T, with parameters.
     * This is like `option` but the value can include parameters, e.g.
     * 'foo:2:3' in place of just 'foo'. The value is a tuple of
     * (basicValue, params) where `basicValue` is whatever would be
     * returned as the value of an `option` and `params` is a string,
     * the raw value of the parameters (including any leading colon).
     * The parameters themselves should be converted by
     * `parseSubParams` or `parseSubParams2`. Any `choices` or
     * `aliasedChoices` specified refer only to the `basicValue`
     * part of the option value.
     */
    def optionWithParams[T](
      name1: String, name2: String = null, name3: String = null,
      name4: String = null, name5: String = null, name6: String = null,
      name7: String = null, name8: String = null, name9: String = null,
      default: (T, String) = (null.asInstanceOf[T], ""),
      metavar: String = null,
      must: Must[T] = null,
      choices: Seq[T] = null,
      aliasedChoices: Seq[Seq[T]] = null,
      help: String = "")
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      optionSeqWithParams[T](nonNullVals(name1, name2, name3, name4, name5,
        name6, name7, name8, name9),
        metavar = metavar, default = default, must = must,
        choices = choices, aliasedChoices = aliasedChoices, help = help
      )(convert, m)
    }

    def flagSeq(name: Seq[String],
      help: String = "") = {
      import ArgotConverters._
      def create_underlying(canon_name: String, canon_metavar: String,
          canon_help: String) = {
        val arg = new ArgFlag(this, canon_name)
        arg.wrap = argot.flag[Boolean](name.toList, canon_help)
        arg
      }
      handle_argument[Boolean,Boolean](name, default = false,
        metavar = null, must = null,
        choices = Seq(true, false), aliasedChoices = null, help = help,
        create_underlying = create_underlying _)
    }

    /**
     * Define a boolean flag option.  Unlike other options, flags have no
     * associated value.  Instead, their type is always Boolean, with the
     * value 'true' if the flag is specified, 'false' if not.
     *
     * @param name1
     * @param name2
     * @param name3
     * @param name4
     * @param name5
     * @param name6
     * @param name7
     * @param name8
     * @param name9
     *    Up to nine aliases for the option; same as for `option[T]()`.
     *
     * @param help Help string for the option, shown in the usage string.
     */
    def flag(name1: String, name2: String = null, name3: String = null,
      name4: String = null, name5: String = null, name6: String = null,
      name7: String = null, name8: String = null, name9: String = null,
      help: String = "") = {
      flagSeq(nonNullVals(name1, name2, name3, name4, name5, name6,
        name7, name8, name9),
        help = help)
    }

    def multiOptionSeq[T](name: Seq[String],
      default: Seq[T] = Seq[T](),
      metavar: String = null,
      must: Must[T] = null,
      choices: Seq[T] = null,
      aliasedChoices: Seq[Seq[T]] = null,
      help: String = "")
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      def create_underlying(canon_name: String, canon_metavar: String,
          canon_help: String) = {
        val checkres = { x: T => checkRestriction(canon_name, x,
          must, choices, aliasedChoices) }
        val arg = new ArgMulti[T](this, canon_name, default, checkres)
        arg.wrap =
          (argot.multiOption[T](name.toList, canon_metavar, canon_help)
           (argot_converter(is_multi = true, convert, canon_name,
             checkres)))
        arg
      }
      handle_argument[T,Seq[T]](name, default, metavar, must,
        choices, aliasedChoices, help, create_underlying _, is_multi = true)
    }

    /**
     * Specify an option that can be repeated multiple times.  The resulting
     * option value will be a sequence (Seq) of all the values given on the
     * command line (one value per occurrence of the option).  If there are
     * no occurrences of the option, the value will be an empty sequence.
     * (NOTE: This is different from single-valued options, where the
     * default value can be explicitly specified, and if not given, will be
     * `null` for reference types.  Here, `null` will never occur.)
     *
     * NOTE: The restrictions specified using `must`, `choices`,
     * `aliasedChoices` apply individually to each value. There is no current
     * way of specifying an overall restriction, e.g. that at least one
     * item must be given. This should be handled separately by the caller.
     *
     * FIXME: There should be a way of allowing for specifying multiple values
     * in a single argument, separated by spaces, commas, etc.  We'd want the
     * caller to be able to pass in a function to split the string.  Currently
     * Argot doesn't seem to have a way of allowing a converter function to
     * take a single argument and stuff in multiple values, so we probably
     * need to modify Argot. (At some point we should just incorporate the
     * relevant parts of Argot directly.)
     */
    def multiOption[T](
      name1: String, name2: String = null, name3: String = null,
      name4: String = null, name5: String = null, name6: String = null,
      name7: String = null, name8: String = null, name9: String = null,
      default: Seq[T] = Seq[T](),
      metavar: String = null,
      must: Must[T] = null,
      choices: Seq[T] = null,
      aliasedChoices: Seq[Seq[T]] = null,
      help: String = "")
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      multiOptionSeq[T](nonNullVals(name1, name2, name3, name4, name5, name6,
        name7, name8, name9),
        metavar = metavar, default = default, must = must,
        choices = choices, aliasedChoices = aliasedChoices, help = help
      )(convert, m)
    }

    /**
     * Specify a positional argument.  Positional argument are processed
     * in order.  Optional argument must occur after all non-optional
     * argument.  The name of the argument is only used in the usage file
     * and as the "name" parameter of the ArgSingle[T] object passed to
     * the (implicit) conversion routine.  Usually the name should be in
     * all caps.
     *
     * @see #multiPositional[T]
     */
    def positional[T](name: String,
      default: T = null.asInstanceOf[T],
      must: Must[T] = null,
      choices: Seq[T] = null,
      aliasedChoices: Seq[Seq[T]] = null,
      help: String = "", optional: Boolean = false)
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      def create_underlying(canon_name: String, canon_metavar: String,
          canon_help: String) = {
        val checkres = { x: T => checkRestriction(canon_name, x,
          must, choices, aliasedChoices) }
        val arg = new ArgSingle(this, canon_name, default, checkres,
          is_positional = true)
        arg.wrap =
          (argot.parameter[T](canon_name, canon_help, optional)
           (argot_converter(is_multi = false, convert, canon_name,
             checkres)))
        arg
      }
      handle_argument[T,T](Seq(name), default, null, must,
        choices, aliasedChoices, help, create_underlying _,
        is_positional = true)
    }

    /**
     * Specify any number of positional arguments.  These must come after
     * all other arguments.
     *
     * @see #positional[T].
     */
    def multiPositional[T](name: String,
      default: Seq[T] = Seq[T](),
      must: Must[T] = null,
      choices: Seq[T] = null,
      aliasedChoices: Seq[Seq[T]] = null,
      help: String = "",
      optional: Boolean = true)
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      def create_underlying(canon_name: String, canon_metavar: String,
          canon_help: String) = {
        val checkres = { x: T => checkRestriction(canon_name, x,
          must, choices, aliasedChoices) }
        val arg = new ArgMulti[T](this, canon_name, default,
          checkres, is_positional = true)
        arg.wrap =
          (argot.multiParameter[T](canon_name, canon_help, optional)
           (argot_converter(is_multi = true, convert, canon_name,
             checkres)))
        arg
      }
      handle_argument[T,Seq[T]](Seq(name), default, null, must,
        choices, aliasedChoices, help, create_underlying _,
        is_multi = true, is_positional = true)
    }

    /**
     * Parse a sub-parameter specified with an argument's value,
     * in an argument specified as with `optionWithParams`, when at most
     * one such sub-parameter can be given.
     *
     * @param argtype Type of argument (usually the basic value of the
     *   argument or some variant); used only for error messages.
     * @param spec The sub-parameter spec, i.e. the string in the second
     *   part of the tuple returned as the value of `optionWithParams` or
     *   the like.
     * @param default Default value of sub-parameter, if not specified.
     * @param convert Function to convert the raw value into a value of
     *   type `T`, as in `option` and the like.
     */
    def parseSubParams[T](argtype: String, spec: String,
      default: T = null.asInstanceOf[T])
    (implicit convert: (String, String, ArgParser) => T, m: Manifest[T]) = {
      val specs = spec.split(":", -1)
      specs.tail.length match {
        case 0 => default
        case 1 =>
          if (specs(1) == "") default
          else convert(specs(1), argtype, this)
        case _ => throw new ArgParserConversionException(
          """too many parameters for type "%s": %s seen, at most 1 allowed"""
          format (argtype, specs.tail.length))
      }
    }

    /**
     * Parse a sub-parameter specified with an argument's value,
     * in an argument specified as with `optionWithParams`, when at most
     * two such sub-parameters can be given.
     *
     * @see #parseSubParams[T]
     */
    def parseSubParams2[T,U](argtype: String, spec: String,
      default: (T,U) = (null.asInstanceOf[T], null.asInstanceOf[U]))
    (implicit convertT: (String, String, ArgParser) => T,
      convertU: (String, String, ArgParser) => U,
      m: Manifest[T]) = {
      val specs = spec.split(":", -1)
      val (deft, defu) = default
      specs.tail.length match {
        case 0 => default
        case 1 => {
          val t =
            if (specs(1) == "") deft
            else convertT(specs(1), argtype, this)
          (t, defu)
        }
        case 2 => {
          val t =
            if (specs(1) == "") deft
            else convertT(specs(1), argtype, this)
          val u =
            if (specs(2) == "") deft
            else convertT(specs(2), argtype, this)
          (t, u)
        }
        case _ => throw new ArgParserConversionException(
          """too many parameters for type "%s": %s seen, at most 2 allowed"""
          format (argtype, specs.tail.length))
      }
    }

    /**
     * Parse the given command-line arguments.  Extracted values of the
     * arguments can subsequently be obtained either using the `#get[T]`
     * function, by directly treating the ArgParser object as if it were
     * a hash table and casting the result, or by using a separate class
     * to hold the extracted values in fields, as described above.  The
     * last method is the recommended one and generally the easiest-to-
     * use for the consumer of the values.
     *
     * @param args Command-line arguments, from main() or the like
     * @param catchErrors If true (the default), usage errors will
     *   be caught, a message outputted (without a stack trace), and
     *   the program will exit.  Otherwise, the errors will be allowed
     *   through, and the application should catch them.
     */
    def parse(args: Seq[String], catchErrors: Boolean = true) = {
      // FIXME: Should we allow this? Not sure if Argot can tolerate this.
      if (parsed)
        throw new ArgParserCodingError("Command-line arguments already parsed")

      if (argmap.size == 0)
        throw new ArgParserCodingError("No arguments initialized.  If you thought you specified arguments, you might have defined the corresponding fields with 'def' instead of 'var' or 'val'.")

      // Call the underlying Argot parsing function and wrap Argot usage
      // errors in our own ArgParserUsageException.
      def call_parse() {
        // println(argmap)
        try {
          val retval = argot.parse(args.toList)
          parsed = true
          retval
        } catch {
          case e: ArgotUsageException => {
            throw new ArgParserUsageException(e.message, Some(e))
          }
        }
      }

      // Reset everything, in case the user explicitly set some values
      // (which otherwise override values retrieved from parsing)
      clear()
      if (catchErrors) catch_parser_errors { call_parse() }
      else call_parse()
    }

    /**
     * Clear all arguments back to their default values.
     */
    def clear() {
      for (obj <- argmap.values) {
        obj.clear()
      }
    }

    def error(msg: String) = {
      throw new ArgParserConversionException(msg)
    }

    def usageError(msg: String) = {
      throw new ArgParserUsageException(msg)
    }
  }
}

object TestArgParser extends App {
  import argparser._
  class MyParams(ap: ArgParser) {
    /* An integer option named --foo, with a default value of 5.  Can also
       be specified using --spam or -f. */
    var foo = ap.option[Int]("foo", "spam", "f", default = 5,
    help="""An integer-valued option.  Default %default.""")
    /* A string option named --bar, with a default value of "chinga".  Can
       also be specified using -b. */
    var bar = ap.option[String]("bar", "b", default = "chinga")
    /* A string option named --baz, which can be given multiple times.
       Default value is an empty sequence. */
    var baz = ap.multiOption[String]("baz")
    /* A floating-point option named --tick, which can be given multiple times.
       Default value is the sequence Seq(2.5, 5.0, 9.0), which will obtain
       when the option hasn't been given at all. */
    var tick = ap.multiOption[Double]("tick", default = Seq(2.5, 5.0, 9.0),
    help = """Option --tick, perhaps for specifying the position of
tick marks along the X axis.  Multiple such options can be given.  If
no marks are specified, the default is %default.  Note that we can
         freely insert
         spaces and carriage
         returns into the help text; whitespace is compressed
      to a single space.""")
    /* A flag --bezzaaf, alias -q.  Value is true if given, false if not. */
    var bezzaaf = ap.flag("bezzaaf", "q")
    /* An integer option --blop, with only the values 1, 2, 4 or 7 are
       allowed.  Default is 1.  Note, in this case, if the default is
       not given, it will end up as 0, even though this isn't technically
       a valid choice.  This could be considered a bug -- perhaps instead
       we should default to the first choice listed, or throw an error.
       (It could also be considered a possibly-useful hack to allow
       detection of when no choice is given; but this can be determined
       in a more reliable way using `ap.specified("blop")`.)
     */
    var blop = ap.option[Int]("blop", default = 1, choices = Seq(1, 2, 4, 7),
    help = """An integral argument with limited choices.  Default is %default,
possible choices are %choices.""")
    /* A string option --daniel, with only the values "mene", "tekel", and
       "upharsin" allowed, but where values can be repeated, e.g.
       --daniel mene --daniel mene --daniel tekel --daniel upharsin
       . */
    var daniel = ap.multiOption[String]("daniel",
      choices = Seq("mene", "tekel", "upharsin"))
    var ranker =
      ap.multiOption[String]("r", "ranker",
        aliasedChoices = Seq(
          Seq("baseline"),
          Seq("none"),
          Seq("full-kl-divergence", "full-kldiv", "full-kl"),
          Seq("partial-kl-divergence", "partial-kldiv", "partial-kl", "part-kl"),
          Seq("symmetric-full-kl-divergence", "symmetric-full-kldiv",
              "symmetric-full-kl", "sym-full-kl"),
          Seq("symmetric-partial-kl-divergence",
              "symmetric-partial-kldiv", "symmetric-partial-kl", "sym-part-kl"),
          Seq("cosine-similarity", "cossim"),
          Seq("partial-cosine-similarity", "partial-cossim", "part-cossim"),
          Seq("smoothed-cosine-similarity", "smoothed-cossim"),
          Seq("smoothed-partial-cosine-similarity", "smoothed-partial-cossim",
              "smoothed-part-cossim"),
          Seq("average-cell-probability", "avg-cell-prob", "acp"),
          Seq("naive-bayes-with-baseline", "nb-base"),
          Seq("naive-bayes-no-baseline", "nb-nobase")),
        help = """A multi-string option.  This is an actual option in
one of my research programs.  Possible choices are %choices; the full list
of choices, including all aliases, is %allchoices.""")
    /* A required positional argument. */
    var destfile = ap.positional[String]("DESTFILE",
      help = "Destination file to store output in")
    /* A multi-positional argument that sucks up all remaining arguments. */
    var files = ap.multiPositional[String]("FILES", help = "Files to process")
  }
  val ap = new ArgParser("test")
  // This first call is necessary, even though it doesn't appear to do
  // anything.  In particular, this ensures that all arguments have been
  // defined on `ap` prior to parsing.
  new MyParams(ap)
  // ap.parse(List("--foo", "7"))
  ap.parse(args)
  val params = new MyParams(ap)
  // Print out values of all arguments, whether options or positional.
  // Also print out types and default values.
  for (name <- ap.argNames)
    println("%30s: %s (%s) (default=%s)" format (
      name, ap(name), ap.getType(name), ap.defaultValue[Any](name)))
  // Examples of how to retrieve individual arguments
  for (file <- params.files)
    println("Process file: %s" format file)
  println("Maximum tick mark seen: %s" format (params.tick max))
  // We can freely change the value of arguments if we want, since they're
  // just vars.
  if (params.daniel contains "upharsin")
    params.bar = "chingamos"
}
