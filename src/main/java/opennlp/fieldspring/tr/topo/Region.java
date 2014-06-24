///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Travis Brown, The University of Texas at Austin
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
package opennlp.fieldspring.tr.topo;

import java.util.List;
import java.io.Serializable;

public abstract class Region implements Serializable {

  private static final long serialVersionUID = 42L;

  public abstract Coordinate getCenter();
  public abstract void setCenter(Coordinate coord);
  public abstract boolean containsRadians(double lat, double lng);

  public abstract double getMinLat();
  public abstract double getMaxLat();
  public abstract double getMinLng();
  public abstract double getMaxLng();
  public abstract List<Coordinate> getRepresentatives();
  public abstract void setRepresentatives(List<Coordinate> coordinates);

  public boolean contains(Coordinate coordinate) {
    return this.containsRadians(coordinate.getLat(), coordinate.getLng());
  }

  public double getMinLatDegrees() {
    return this.getMinLat() * Math.PI / 180.0;
  }

  public double getMaxLatDegrees() {
    return this.getMaxLat() * Math.PI / 180.0;
  }

  public double getMinLngDegrees() {
    return this.getMinLng() * Math.PI / 180.0;
  }

  public double getMaxLngDegrees() {
    return this.getMaxLng() * Math.PI / 180.0;
  }

  public double distance(Region other) {
    //return this.distance(other.getCenter());
    double minDist = Double.POSITIVE_INFINITY;
    for(Coordinate coord : this.getRepresentatives()) {
        for(Coordinate otherCoord : other.getRepresentatives()) {
            double curDist = coord.distance(otherCoord);
            if(curDist < minDist)
                minDist = curDist;
        }
    }
    return minDist;
  }

  public double distance(Coordinate coordinate) {
    //return this.getCenter().distance(coordinate);
    double minDist = Double.POSITIVE_INFINITY;
    for(Coordinate coord : this.getRepresentatives()) {
        double curDist = coord.distance(coordinate);
        if(curDist < minDist)
            minDist = curDist;
    }
    return minDist;
  }

  public double distanceInKm(Region other) {
      if(this.getRepresentatives().size() == 1 && other.getRepresentatives().size() == 1)
          return this.getCenter().distanceInKm(other.getCenter());
    //return this.distanceInKm(other.getCenter());
    /*double minDist = Double.POSITIVE_INFINITY;
    for(Coordinate coord : this.getRepresentatives()) {
        for(Coordinate otherCoord : other.getRepresentatives()) {
            double curDist = coord.distanceInKm(otherCoord);
            if(curDist < minDist)
                minDist = curDist;
        }
    }
    return minDist;*/
    return 6372.8 * this.distance(other);
  }
}

