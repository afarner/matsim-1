/* *********************************************************************** *
 * project: org.matsim.*
 * DgMfd
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
package playground.dgrether.koehlerstrehlersignal.analysis;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.AgentArrivalEvent;
import org.matsim.core.api.experimental.events.AgentDepartureEvent;
import org.matsim.core.api.experimental.events.AgentStuckEvent;
import org.matsim.core.api.experimental.events.LinkEnterEvent;
import org.matsim.core.api.experimental.events.LinkLeaveEvent;
import org.matsim.core.api.experimental.events.handler.AgentArrivalEventHandler;
import org.matsim.core.api.experimental.events.handler.AgentDepartureEventHandler;
import org.matsim.core.api.experimental.events.handler.AgentStuckEventHandler;
import org.matsim.core.api.experimental.events.handler.LinkEnterEventHandler;
import org.matsim.core.api.experimental.events.handler.LinkLeaveEventHandler;


/**
 * @author dgrether
 *
 */
public class DgMfd implements LinkEnterEventHandler, LinkLeaveEventHandler, AgentArrivalEventHandler, AgentStuckEventHandler, AgentDepartureEventHandler {
	
	private static final Logger log = Logger.getLogger(DgMfd.class);
	
	private double binSizeSeconds = 1.0 * 60.0;
	private Map<Id, Double> firstTimeSeenMap = new HashMap<Id, Double>();
	private Map<Id, LinkLeaveEvent> lastTimeSeenMap = new HashMap<Id, LinkLeaveEvent>();
	private int iteration = 0;
	private Network network;
	private double networkLength;
	private Data data = new Data();
	private double vehicleSize = 7.5 * 1.0/0.7;

	
	public DgMfd(Network network){
		this.network = network;
		this.networkLength =  this.calcNetworkLength();
	}

	@Override
	public void reset(int iteration) {
		this.iteration = iteration;
		this.firstTimeSeenMap.clear();
		this.lastTimeSeenMap.clear();
		this.data.reset();
	}

	
	@Override
	public void handleEvent(LinkEnterEvent event) {
		if (this.network.getLinks().containsKey(event.getLinkId())) {
			if (! this.firstTimeSeenMap.containsKey(event.getPersonId())) {
				this.firstTimeSeenMap.put(event.getPersonId(), event.getTime());
			} 
		}
		else if (this.firstTimeSeenMap.containsKey(event.getPersonId())){
			this.handleLeaveNetworkOrArrivalOrStuck(event.getPersonId());
		}
	}
	
	@Override
	public void handleEvent(AgentDepartureEvent event) {
		if (this.network.getLinks().containsKey(event.getLinkId())){
			this.firstTimeSeenMap.put(event.getPersonId(), event.getTime());
		}
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
		if (this.network.getLinks().containsKey(event.getLinkId())) {
			this.lastTimeSeenMap.put(event.getPersonId(), event);
		}

	}
	
	@Override
	public void handleEvent(AgentStuckEvent event) {
		this.handleLeaveNetworkOrArrivalOrStuck(event.getPersonId());		
	}
	
	@Override
	public void handleEvent(AgentArrivalEvent event) {
		this.handleLeaveNetworkOrArrivalOrStuck(event.getPersonId());
	}

	
	private void handleLeaveNetworkOrArrivalOrStuck(Id personId) {
		Double firstEvent = this.firstTimeSeenMap.remove(personId);
		LinkLeaveEvent lastEvent = this.lastTimeSeenMap.remove(personId);
		
		if (firstEvent != null && lastEvent != null){
			int index = getBinIndex(firstEvent);
			this.data.incrementDepartures(index);
			index = getBinIndex(lastEvent.getTime());
			this.data.incrementArrivals(index);
		}
//		else {
//			log.warn("No first or last event found for person id: " + personId);
//		}
	}

	
	
	private int getBinIndex(double time){
		return (int)(time / this.binSizeSeconds);
	}
	

	
	private double calcNetworkLength() {
		double length = 0.0;
		for (Link l : this.network.getLinks().values()){
			length += (l.getLength() * l.getNumberOfLanes());
		}
		return length / 1000.0;
	}


	public void writeFile(String filename) {
		PrintStream stream;
		try {
			stream = new PrintStream(new File(filename));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return;
		}
		write(stream);
		stream.close();
	}
	
	public void write(final PrintStream stream) {
		String header  = "slot\ttime[s]\thour\tdepartures\tarrivals\ten-route\tdensity[veh/km]\tnetworkLength[km]";
		stream.println(header);
		double density = 0.0;
		Integer arnew = 0;
		Integer depnew = 0;
		double noVehicles = 0;
		for (Entry<Integer, Integer> e :  this.data.getDeparturesBySlot().entrySet()){
			arnew = this.data.getArrivalsBySlot().get(e.getKey());
			if (arnew == null) {
				arnew = 0;
			}
			depnew = e.getValue();
			noVehicles = noVehicles + depnew - arnew ;
			density = noVehicles * this.vehicleSize / this.networkLength;
			double timeSec = e.getKey() * this.binSizeSeconds;
			int hour = (int) (timeSec / 3600);	
			
			StringBuffer line = new StringBuffer();
			line.append(e.getKey());
			line.append("\t");
			line.append(timeSec);
			line.append("\t");
			line.append(hour);
			line.append("\t");
			line.append(depnew);
			line.append("\t");
			line.append(arnew);
			line.append("\t");
			line.append(noVehicles);
			line.append("\t");
			line.append(density);
			line.append("\t");
			line.append(networkLength);
			
			stream.println(line.toString());
		}
	}
	
	private static class Data {
		
		private SortedMap<Integer, Integer> arrivalsBySlot = new TreeMap<Integer, Integer>();
		private SortedMap<Integer, Integer> departuresBySlot = new TreeMap<Integer, Integer>();
		
		public Data() {}
		
		public void incrementArrivals(Integer slot){
			if (! this.arrivalsBySlot.containsKey(slot)){
				this.arrivalsBySlot.put(slot, 0);
			}
			this.arrivalsBySlot.put(slot, (this.arrivalsBySlot.get(slot) + 1));
		}
		
		public void incrementDepartures(Integer slot) {
			if (! this.departuresBySlot.containsKey(slot)){
				this.departuresBySlot.put(slot, 0);
			}
			this.departuresBySlot.put(slot, (this.departuresBySlot.get(slot) + 1));
		}
		
		public void reset(){
			this.arrivalsBySlot.clear();
			this.departuresBySlot.clear();
		}

		
		public Map<Integer, Integer> getArrivalsBySlot() {
			return arrivalsBySlot;
		}

		
		public Map<Integer, Integer> getDeparturesBySlot() {
			return departuresBySlot;
		}
		
	}
	
	

}
