/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.transEnergySim.controllers;

import java.util.HashMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.transEnergySim.charging.ChargingUponArrival;
import org.matsim.contrib.transEnergySim.chargingInfrastructure.road.InductiveStreetCharger;
import org.matsim.contrib.transEnergySim.vehicles.api.Vehicle;
import org.matsim.contrib.transEnergySim.vehicles.energyConsumption.EnergyConsumptionTracker;
import org.matsim.contrib.transEnergySim.vehicles.energyConsumption.api.EnergyConsumptionModel;
import org.matsim.contrib.transEnergySim.vehicles.energyConsumption.galus.EnergyConsumptionModelGalus;
import org.matsim.contrib.transEnergySim.vehicles.impl.IC_BEV;
import org.matsim.core.basic.v01.IdImpl;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.testcases.MatsimTestCase;
/**
 * @author wrashid
 *
 */
public class TestInductiveChargingController extends MatsimTestCase {

	public void testBasic2(){
		Config config= loadConfig(getClassInputDirectory()+"config.xml");
		
		EnergyConsumptionModel ecm=new EnergyConsumptionModelGalus();
		HashMap<Id, Vehicle> vehicles=new HashMap<Id, Vehicle>();
		vehicles.put(new IdImpl("1"), new IC_BEV(ecm,10*1000*3600));
		
		InductiveChargingController controller = new InductiveChargingController(config,vehicles);

		InductiveStreetCharger inductiveCharger = controller.getInductiveCharger();
		inductiveCharger.allStreetsCanChargeWithPower(3000);
		
		ChargingUponArrival chargingUponArrival= controller.getChargingUponArrival();
		chargingUponArrival.setPowerAvailableAtAllActivityTypesTo(controller.getFacilities(), 0);
		chargingUponArrival.getChargablePowerAtActivityTypes().put("home", 3500.0);
		chargingUponArrival.getChargablePowerAtActivityTypes().put("work", 3500.0);
		
		
		EnergyConsumptionTracker energyConsumptionTracker = controller.getEnergyConsumptionTracker();
		
		controller.setOverwriteFiles(true);
		
		controller.run();

		//assertEquals(1.0, inductiveCharger.getLog().get(0).getEnergyChargedInJoule());
		//assertEquals(1.0, chargingUponArrival.getLog().get(0).getEnergyChargedInJoule());
		assertEquals(1.0, energyConsumptionTracker.getLog().get(0).getEnergyConsumedInJoules());
		//TODO: check also, if the energy consumption functions correctly
		
	}
}
