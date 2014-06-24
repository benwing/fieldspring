///////////////////////////////////////////////////////////////////////////////
//  spherical.scala
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

import scala.math._

import net.liftweb

import coord.CoordHandler
import error.warning
import math.MeanShift
import serialize.TextSerializer

/*
  The coordinates of a point are spherical coordinates, indicating a
  latitude and longitude.  Latitude ranges from -90 degrees (south) to
  +90 degrees (north), with the Equator at 0 degrees.  Longitude ranges
  from -180 degrees (west) to +179.9999999.... degrees (east). -180 and +180
  degrees correspond to the same north-south parallel, and we arbitrarily
  choose -180 degrees over +180 degrees.  0 degrees longitude has been
  arbitrarily chosen as the north-south parallel that passes through
  Greenwich, England (near London).  Note that longitude wraps around, but
  latitude does not.  Furthermore, the distance between latitude lines is
  always the same (about 111 km per degree, or about 69 miles per degree),
  but the distance between longitude lines varies according to the
  latitude, ranging from about 111 km per degree at the Equator to 0 km
  at the North and South Pole.
*/

/**
  Singleton object holding information of various sorts related to distances
  on the Earth and coordinates, objects for handling coordinates and cell
  indices, and miscellaneous functions for computing distance and converting
  between different coordinate formats.

  The following is contained:

  1. Fixed information: e.g. radius of Earth in kilometers (km), number of
     km per degree at the Equator, number of km per mile, minimum/maximum
     latitude/longitude.

  2. The SphereCoord class (holding a latitude/longitude pair)

  3. Function spheredist() to compute spherical (great-circle) distance
     between two SphereCoords; likewise degree_dist() to compute degree
     distance between two SphereCoords
 */
protected class SphericalPackage {

  /***** Fixed values *****/

  val minimum_latitude = -90.0
  val maximum_latitude = 90.0
  val minimum_longitude = -180.0
  // We consider this value valid in external sources, but internally
  // we coerce it to -180 degrees.
  val maximum_longitude = 180.0

  // Radius of the earth in km.  Used to compute spherical distance in km,
  // and km per degree of latitude/longitude.
  // val earth_radius_in_miles = 3963.191
  val earth_radius_in_km = 6376.774

  // Number of kilometers per mile.
  val km_per_mile = 1.609

  // Number of km per degree, at the equator.  For longitude, this is the
  // same everywhere, but for latitude it is proportional to the degrees away
  // from the equator.
  val km_per_degree = Pi * 2 * earth_radius_in_km / 360.0

  // Number of miles per degree, at the equator.
  val miles_per_degree = km_per_degree / km_per_mile

  def km_and_miles(kmdist: Double) = {
    "%.2f km (%.2f miles)" format (kmdist, kmdist / km_per_mile)
  }

  // A 2-dimensional coordinate.
  //
  // The following fields are defined:
  //
  //   lat, long: Latitude and longitude of coordinate.

  case class SphereCoord(lat: Double, var long: Double) {
    // Not sure why this code was implemented with coerce_within_bounds,
    // but either always coerce, or check the bounds ...
    // FIXME: This should not be a case class, and instead we should
    // control access to constructor so we don't have to have the
    // 'var' in the long.
    if (long == maximum_longitude)
      long = minimum_longitude
    require(SphereCoord.valid_internal(lat, long),
      "Coordinates out of bounds: %s" format toString)
    override def toString = SphereCoord.serialize(this)
    def format = SphereCoord.format_lat_long(lat, long)
  }

  implicit val SphereCoordOrdering =
    Ordering[(Double, Double)].on((x: SphereCoord) => (x.lat, x.long))

  implicit object SphereCoord extends TextSerializer[SphereCoord] {
    def format_lat_long(lat: Double, long: Double) =
      "(%.2f,%.2f)".format(lat, long)

    /** Look up a lat/long coord and return the nearest "place" to the
      * coord, using MapQuest. E.g. -37.8,122.0 maps to
      * Some("Montego Drive, Danville, Contra Costa, California, 94526, United States of America")
      *
      * Return value is None when no place could be found.
      */
    def lookup_lat_long_mapquest(lat: Double, long: Double) = {
      val mapquest_url =
        "http://open.mapquestapi.com/nominatim/v1/search?q=%s,%s&format=json" format (lat, long)
      val response = scala.io.Source.fromURL(mapquest_url).mkString
      val parsed = liftweb.json.parse(response)
      val places = parsed.values.asInstanceOf[List[Map[String, Any]]]
      if (places.length == 0) None
      else Some(places(0)("display_name").asInstanceOf[String])
    }

    // Create a coord, with METHOD defining how to handle coordinates
    // out of bounds.  If METHOD =  "validate", check within bounds,
    // and abort if not.  If "coerce", coerce within bounds (latitudes
    // are cropped, longitudes are taken mod 360).  If "coerce-warn",
    // same as "coerce" but also issue a warning when coordinates are
    // out of bounds.
    def apply(lat: Double, long: Double, method: String) = {
      val (newlat, newlong) =
        method match {
          case "coerce-warn" => {
            if (!valid_external(lat, long))
              warning("Coordinates out of bounds: %s",
                format_lat_long(lat, long))
            coerce(lat, long)
          }
          case "coerce" => coerce(lat, long)
          case "validate" => (lat, long)
          case "allow" => (lat, long)
          case _ => {
            require(false,
                    "Invalid method to SphereCoord(): %s" format method)
            (0.0, 0.0)
          }
        }
      new SphereCoord(newlat, newlong)
    }

    /**
     * Whether the given latitude and longitude are internally valid,
     * i.e. validly storable as the coordinates of a SphereCoord.
     */
    def valid_internal(lat: Double, long: Double) = (
      lat >= minimum_latitude &&
      lat <= maximum_latitude &&
      long >= minimum_longitude &&
      long < maximum_longitude
    )

    /**
     * Whether the given external latitude and longitude are valid.
     * The only difference is in longitude +180 degrees, which we
     * accept but map internally to -180 degrees.
     */
    def valid_external(lat: Double, long: Double) = (
      lat >= minimum_latitude &&
      lat <= maximum_latitude &&
      long >= minimum_longitude &&
      long <= maximum_longitude
    )

    def coerce(lat: Double, long: Double) = {
      var newlat = lat
      var newlong = long
      // Truncate out-of-bounds latitudes, but wrap out-of-bounds
      // longitudes.
      if (newlat > maximum_latitude) newlat = maximum_latitude
      while (newlong >= maximum_longitude) newlong -= 360.0
      if (newlat < minimum_latitude) newlat = minimum_latitude
      while (newlong < minimum_longitude) newlong += 360.0
      (newlat, newlong)
    }

    def deserialize(foo: String) = {
      val foo_stripped = foo.stripPrefix("(").stripSuffix(")")
      val Array(lat, long) = foo_stripped.split(",", -1)
      SphereCoord(lat.toDouble, long.toDouble)
    }

    def serialize(foo: SphereCoord) = "%s,%s".format(foo.lat, foo.long)

    /** Compute the centroid of a set of points.
     *
     * FIXME! This does not work correctly if the points span the 180th
     * parallel longitude and will often not work correctly if the points
     * span more than 180 degrees longitude. Properly, the way to average
     * a series of longitudes is something like this:
     *
     * 1. Figure out the largest span between any two parallels that does
     *    not cross any other parallels.
     * 2. The more westward of the two parallels when moving along this span
     *    needs to be the maximum, and the more eastward of the two
     *    needs to be the minimum.
     * 3. Shift the parallels that are numerically below the designated
     *    minimum parallel up by 360 degrees, then compute the average
     *    in the normal fashion.
     */
    def centroid(points: Iterable[SphereCoord]) = {
      val lats = points.map(_.lat)
      val longs = points.map(_.long)
      SphereCoord(lats.sum / lats.size, longs.sum / longs.size)
    }

    /** Compute the southwest (min) bounding box corner of a set of points.
     *
     * FIXME! This does not work correctly if the points span the 180th
     * parallel longitude and will often not work correctly if the points
     * span more than 180 degrees longitude. See `centroid`; we need to do
     * the same thing.
     */
    def bounding_box_sw(points: Iterable[SphereCoord]) =
      SphereCoord(points.map(_.lat).min, points.map(_.long).min)

    /** Compute the northeast (max) bounding box corner of a set of points.
     *
     * FIXME! This does not work correctly if the points span the 180th
     * parallel longitude and will often not work correctly if the points
     * span more than 180 degrees longitude. See `centroid`; we need to do
     * the same thing.
     */
    def bounding_box_ne(points: Iterable[SphereCoord]) =
      SphereCoord(points.map(_.lat).max, points.map(_.long).max)
  }

  implicit object SphereCoordHandler extends CoordHandler[SphereCoord] {
    def format_coord(coord: SphereCoord) = coord.format
    def less_than_coord(c1: SphereCoord, c2: SphereCoord) = {
      if (c1.lat == c2.lat)
        c1.long < c2.long
      else
        c1.lat < c2.lat
    }

    def distance_between_coords(c1: SphereCoord, c2: SphereCoord) =
      spheredist(c1, c2)
    def output_distance(dist: Double) = km_and_miles(dist)
  }

  case class BoundingBox(sw: SphereCoord, ne: SphereCoord) {
    require(sw.lat <= ne.lat)
    override def toString = BoundingBox.serialize(this)

    /** True if this bounding box overlaps the specified bounding box
      * other than just at a point or line.
      * FIXME: Doesn't handle bounding boxes that cross +-180 longitude. */
    def overlaps(x: BoundingBox) = {
      val outside_ne = sw.lat >= x.ne.lat || sw.long >= x.ne.long
      val outside_sw = ne.lat <= x.sw.lat || ne.long <= x.sw.long
      val outside = outside_ne || outside_sw
      !outside
    }

    /** True if this bounding box overlaps the specified bounding box
      * or touches at a point or line.
      * FIXME: Doesn't handle bounding boxes that cross +-180 longitude. */
    def overlaps_or_touches(x: BoundingBox) = {
      val outside_ne = sw.lat > x.ne.lat || sw.long > x.ne.long
      val outside_sw = ne.lat < x.sw.lat || ne.long < x.sw.long
      val outside = outside_ne || outside_sw
      !outside
    }
  }

  implicit object BoundingBox extends TextSerializer[BoundingBox] {
    def deserialize(foo: String) = {
      val Array(sw, ne) = foo.split(":", -1)
      BoundingBox(SphereCoord.deserialize(sw), SphereCoord.deserialize(ne))
    }

    def serialize(foo: BoundingBox) =
      "%s:%s".format(SphereCoord.serialize(foo.sw), SphereCoord.serialize(foo.ne))
  }

  // Compute spherical distance in km (along a great circle) between two
  // coordinates.

  def spheredist(p1: SphereCoord, p2: SphereCoord): Double = {
    if (p1 == null || p2 == null) return 1000000.0
    val thisRadLat = (p1.lat / 180.0) * Pi
    val thisRadLong = (p1.long / 180.0) * Pi
    val otherRadLat = (p2.lat / 180.0) * Pi
    val otherRadLong = (p2.long / 180.0) * Pi

    val anglecos = (sin(thisRadLat)*sin(otherRadLat)
                + cos(thisRadLat)*cos(otherRadLat)*
                  cos(otherRadLong-thisRadLong))
    // If the values are extremely close to each other, the resulting cosine
    // value will be extremely close to 1.  In reality, however, if the values
    // are too close (e.g. the same), the computed cosine will be slightly
    // above 1, and acos() will complain.  So special-case this.
    if (abs(anglecos) > 1.0) {
      if (abs(anglecos) > 1.000001) {
        warning("Something wrong in computation of spherical distance, out-of-range cosine value %f",
          anglecos)
        return 1000000.0
      } else
        return 0.0
    }
    return earth_radius_in_km * acos(anglecos)
  }

  def degree_dist(c1: SphereCoord, c2: SphereCoord) = {
    sqrt((c1.lat - c2.lat) * (c1.lat - c2.lat) +
      (c1.long - c2.long) * (c1.long - c2.long))
  }

  /**
   * Square area in km^2 of a rectangle on the surface of a sphere made up
   * of latitude and longitude lines. (Although the parameters below are
   * described as bottom-left and top-right, respectively, the function as
   * written is in fact insensitive to whether bottom-left/top-right or
   * top-left/bottom-right pairs are given, and which order they are
   * given.  All that matters is that opposite corners are supplied.  The
   * use of `abs` below takes care of this.)
   *
   * @param botleft Coordinate of bottom left of rectangle
   * @param topright Coordinate of top right of rectangle
   */
  def square_area(botleft: SphereCoord, topright: SphereCoord) = {
    var (lat1, lon1) = (botleft.lat, botleft.long)
    var (lat2, lon2) = (topright.lat, topright.long)
    lat1 = (lat1 / 180.0) * Pi
    lat2 = (lat2 / 180.0) * Pi
    lon1 = (lon1 / 180.0) * Pi
    lon2 = (lon2 / 180.0) * Pi

    (earth_radius_in_km * earth_radius_in_km) *
      abs(sin(lat1) - sin(lat2)) *
      abs(lon1 - lon2)
  }

  /**
   * Average two longitudes.  This is a bit tricky because of the way
   * they wrap around.
   */
  def average_longitudes(long1: Double, long2: Double): Double = {
    if (long1 - long2 > 180.0)
      average_longitudes(long1 - 360.0, long2)
    else if (long2 - long1 > 180.0)
      average_longitudes(long1, long2 - 360.0)
    else
      (long1 + long2) / 2.0
  }

  // FIXME: Not yet tested!!
//  class GrahamScan {
//    // Graham Scan - Based on Python code from
//    // Tom Switzer <thomas.switzer@gmail.com>.
//    //
//    // FIXME: This is actually designed for Euclidean geometry, not
//    // spherical geometry.
//    //
//    // FIXME: This will have problems if points wrap across the
//    // -180/+180 line.
//    //
//    // FIXME: Rewrite this with a type parameter to allow it to be
//    // applied to other types of 2-d points.
//
//    protected val TURN_LEFT = 1
//    protected val TURN_RIGHT = -1
//    protected val TURN_NONE = 0
//
//    protected def turn(p: SphereCoord, q: SphereCoord, r: SphereCoord) =
//      ((q.lat - p.lat)*(r.long - p.long) - (r.lat - p.lat)*(q.long - p.long)
//        ) compare 0
//
//    protected def keep_left(hull: IndexedSeq[SphereCoord], r: SphereCoord) = {
//      var h = hull
//      while (h.size > 1 && turn(h(h.size - 2), h(h.size - 1), r) != TURN_LEFT)
//        h = h.dropRight(1)
//      if (h.size == 0 || h.last != r)
//        h :+= r
//      h
//    }
//
//    /**
//     * Returns points on convex hull of an array of points in CCW order.
//     */
//    def convex_hull(points: IndexedSeq[SphereCoord]) = {
//      val sortpoints = points.sorted
//      val l = sortpoints.foldLeft(IndexedSeq[SphereCoord]())(keep_left)
//      val u = sortpoints.reverse.foldLeft(IndexedSeq[SphereCoord]())(keep_left)
//      l ++ u.drop(1).dropRight(1)
//    }
//  }

  class SphereMeanShift(
    h: Double = 1.0,
    max_stddev: Double = 1e-10,
    max_iterations: Int = 100
  ) extends MeanShift[SphereCoord](h, max_stddev, max_iterations) {
    def squared_distance(x: SphereCoord, y:SphereCoord) = {
      val dist = spheredist(x, y)
      dist * dist
    }

    def weighted_sum(weights:Array[Double], points:Array[SphereCoord]) = {
      val len = weights.length
      var lat = 0.0
      var long = 0.0
      for (i <- 0 until len) {
        val w = weights(i)
        val c = points(i)
        lat += c.lat * w
        long += c.long * w
      }
      SphereCoord(lat, long)
    }

    def scaled_sum(scalar:Double, points:Array[SphereCoord]) = {
      var lat = 0.0
      var long = 0.0
      for (c <- points) {
        lat += c.lat * scalar
        long += c.long * scalar
      }
      SphereCoord(lat, long)
    }
  }
}

package object spherical extends SphericalPackage { }

