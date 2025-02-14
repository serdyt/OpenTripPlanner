/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.model.json_serialization;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.opentripplanner.util.PolylineEncoder;



import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

public class EncodedPolylineJSONSerializer extends JsonSerializer<Geometry> {

    @Override
    public void serialize(Geometry arg, JsonGenerator jgen, SerializerProvider provider)
            throws IOException, JsonProcessingException {
        
        if (arg == null) {
            jgen.writeNull();
        }
        Coordinate[] lineCoords = arg.getCoordinates();
        List<Coordinate> coords = Arrays.asList(lineCoords);
        
        jgen.writeObject(PolylineEncoder.createEncodings(coords).getPoints());
    }

    @Override
    public Class<Geometry> handledType() {
        return Geometry.class;
    }
}
