/**
 * 
 */
package playground.kai.urbansim;

import org.matsim.api.basic.v01.population.BasicPopulation;
import org.matsim.core.api.experimental.population.Population;
import org.matsim.core.facilities.ActivityFacilities;
import org.matsim.world.Layer;

/**
 * Simple interface so that ReadFromUrbansimCellModel and ReadFromUrbansimParcelModel can be called via the same syntax.
 * Not used.
 * 
 * @author nagel
 *
 */
@Deprecated
public interface ReadFromUrbansim {
	

	public void readFacilities ( ActivityFacilities facilities ) ;

	public void readPersons( Population population, ActivityFacilities facilities, double fraction ) ;
	
	public void readZones( ActivityFacilities zones, Layer parcels ) ;
}
