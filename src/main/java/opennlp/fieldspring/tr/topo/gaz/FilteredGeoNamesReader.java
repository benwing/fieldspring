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
package opennlp.fieldspring.tr.topo.gaz;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.BufferedReader;

import opennlp.fieldspring.tr.topo.Location;
import opennlp.fieldspring.tr.util.IOUtil;

public class FilteredGeoNamesReader extends GeoNamesReader {
  public FilteredGeoNamesReader(File file) throws FileNotFoundException, IOException {
    this(IOUtil.createBufferedReader(file));
  }

  public FilteredGeoNamesReader(BufferedReader reader)
    throws FileNotFoundException, IOException {
    super(reader);
  }

  protected Location parseLine(String line, int currentId) {
    Location location = super.parseLine(line, currentId);
    if (location != null) {
      Location.Type type = location.getType();
      if (type != Location.Type.STATE && type != Location.Type.CITY) {
        location = null;
      }
    }
    return location;  
  }
}

