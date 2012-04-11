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

package org.matsim.contrib.cadyts.pt;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.replanning.PlanStrategyModule;
import org.matsim.core.basic.v01.IdImpl;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.ControlerIO;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.selectors.PlanSelector;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.counts.Counts;
import org.matsim.counts.MatsimCountsReader;
import org.matsim.pt.config.PtCountsConfigGroup;
import org.matsim.pt.counts.PtCountSimComparisonKMLWriter;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import cadyts.interfaces.matsim.MATSimUtilityModificationCalibrator;
import cadyts.measurements.SingleLinkMeasurement.TYPE;
import cadyts.supply.SimResults;

public class NewPtBsePlanStrategy implements PlanStrategy, IterationEndsListener, BeforeMobsimListener, AfterMobsimListener {

	private final static Logger log = Logger.getLogger(NewPtBsePlanStrategy.class);

	private final static String LINKOFFSET_FILENAME = "linkCostOffsets.xml";
	private static final String FLOWANALYSIS_FILENAME = "flowAnalysis.txt";
	private static final String OCCUPANCYANALYSIS_FILENAME = "ptBseOccupancyAnalysis.txt";

	private PlanStrategy delegate = null;

	private MATSimUtilityModificationCalibrator<TransitStopFacility> calibrator = null;
	private final SimResultsContainerImpl simResults;
	private final double countsScaleFactor;
	private final Counts occupCounts = new Counts();
	private final Counts boardCounts = new Counts();
	private final Counts alightCounts = new Counts();
	private final PtBseOccupancyAnalyzer ptBseOccupAnalyzer;
	static TransitSchedule trSched;
	private final boolean writeAnalysisFile;
	NewPtBsePlanChanger ptBsePlanChanger;
	final Set<Id> analyzedLines = new HashSet<Id>();

	public NewPtBsePlanStrategy(final Controler controler) { // DO NOT CHANGE CONSTRUCTURE, needed for reflection-based instantiation
		this.analyzedLines.add(new IdImpl("M44")); // TODO make configurable

		controler.addControlerListener(this);

		this.ptBseOccupAnalyzer = new PtBseOccupancyAnalyzer();
		controler.getEvents().addHandler(this.ptBseOccupAnalyzer);

		this.simResults = new SimResultsContainerImpl(this.ptBseOccupAnalyzer);

		// this collects events and generates cadyts plans from it
		PtPlanToPlanStepBasedOnEvents ptStep = new PtPlanToPlanStepBasedOnEvents(controler.getScenario());
		controler.getEvents().addHandler(ptStep);

		// build the calibrator. This is a static method, and in consequence has no side effects
		this.calibrator = CadytsBuilder.buildCalibrator(controler.getScenario());

		// finally, we create the PlanStrategy, with the bse-based plan selector:
		this.ptBsePlanChanger = new NewPtBsePlanChanger(ptStep, this.calibrator);
		this.delegate = new PlanStrategyImpl(this.ptBsePlanChanger);

		// NOTE: The coupling between calibrator and simResults is done in "reset".

		// ===========================
		// everything beyond this line is, I think, analysis code. kai, jul'11

		// read occup counts from file
		String occupancyCountsFilename = controler.getConfig().ptCounts().getOccupancyCountsFileName();
		if (occupancyCountsFilename != null) {
			new MatsimCountsReader(this.occupCounts).readFile(occupancyCountsFilename);
		}
		// yyyyyy the counts data is read in "buildCalibrator", and here again. This is not necessary,
		// and confuses the reader of the program. kai, jul'11

		this.countsScaleFactor = controler.getConfig().ptCounts().getCountsScaleFactor();

		// set flowAnalysisFile
		String strWriteAnalysisFile = controler.getConfig().findParam(CadytsPtConfigGroup.GROUP_NAME, "writeAnalysisFile");
		this.writeAnalysisFile = strWriteAnalysisFile != null && Boolean.parseBoolean(strWriteAnalysisFile);
		strWriteAnalysisFile = null;

		controler.getScenario().addScenarioElement(this.occupCounts);
	}

	// Analysis methods
	// /////////////////////////////////////////////////////
	@Override
	public void notifyBeforeMobsim(final BeforeMobsimEvent event) {
		this.ptBseOccupAnalyzer.reset(event.getIteration());
	}

	@Override
	public void notifyAfterMobsim(final AfterMobsimEvent event) {
		int it = event.getIteration();
		if (isActiveInThisIteration(it, event.getControler())) {
			// Get all stations of all analyzed lines and invoke the method write to get all information of them
			Set<Id> stopIds = new HashSet<Id>();
			for (Id lineId : this.analyzedLines) {
				TransitLine line = event.getControler().getScenario().getTransitSchedule().getTransitLines().get(lineId);
				for (TransitRoute route : line.getRoutes().values()) {
					for (TransitRouteStop stop : route.getStops()) {
						stopIds.add(stop.getStopFacility().getId());
					}
				}
			}
			String outFile = event.getControler().getControlerIO().getIterationFilename(it, OCCUPANCYANALYSIS_FILENAME);
			this.ptBseOccupAnalyzer.writeResultsForSelectedStopIds(outFile, this.occupCounts, stopIds);
		}
	}

	private boolean isActiveInThisIteration(final int iter, final Controler controler) {
		return (iter % controler.getConfig().ptCounts().getPtCountsInterval() == 0) && (iter >= controler.getFirstIteration());
	}

	@Override
	public void notifyIterationEnds(final IterationEndsEvent event) {
		if (this.writeAnalysisFile) {
			String analysisFilepath = null;
			if (isActiveInThisIteration(event.getIteration(), event.getControler())) {
				analysisFilepath = event.getControler().getControlerIO().getIterationFilename(event.getIteration(), FLOWANALYSIS_FILENAME);
			}
			this.calibrator.setFlowAnalysisFile(analysisFilepath);
		}

		this.calibrator.afterNetworkLoading(this.simResults);

		// the remaining material is, in my view, "just" output:
		String filename = event.getControler().getControlerIO().getIterationFilename(event.getIteration(), LINKOFFSET_FILENAME);
		try {
			PtBseLinkCostOffsetsXMLFileIO ptBseLinkCostOffsetsXMLFileIO = new PtBseLinkCostOffsetsXMLFileIO(trSched);
			ptBseLinkCostOffsetsXMLFileIO.write(filename, this.calibrator.getLinkCostOffsets());
			ptBseLinkCostOffsetsXMLFileIO = null;
		} catch (IOException e) {
			log.error(e);
		}

		generateAndWriteCountsComparisons(event);
	}

	/*package*/ MATSimUtilityModificationCalibrator<TransitStopFacility> getCalibrator() {
		// for testing purposes only
		return this.calibrator;
	}

	// ===========================================================================================================================
	// private methods & pure delegate methods only below this line
	// yyyyyy this statement is no longer correct since someone added other public methods below. kai,jul'11

	private void generateAndWriteCountsComparisons(final IterationEndsEvent event) {
		Config config = event.getControler().getConfig();
		PtCountsConfigGroup ptCounts = config.ptCounts();
		if (ptCounts.getAlightCountsFileName() != null) { // yyyy this check should reasonably also be done in isActiveInThisIteration. kai,oct'10
			Controler controler = event.getControler();
			int iter = event.getIteration();
			if (isActiveInThisIteration(iter, controler)) {

				if (config.ptCounts().getPtCountsInterval() != 10)
					log.warn("yyyy This may not work when the pt counts interval is different from 10 because I think I changed things at two "
							+ "places but I can't find the other one any more :-(.  (May just be inefficient.)  kai, oct'10");

				controler.stopwatch.beginOperation("compare with pt counts");

				Network network = controler.getNetwork();
				PtBseCountsComparisonAlgorithm ccaBoard = new PtBseCountsComparisonAlgorithm(this.ptBseOccupAnalyzer, this.boardCounts,
						network, this.countsScaleFactor);
				PtBseCountsComparisonAlgorithm ccaAlight = new PtBseCountsComparisonAlgorithm(this.ptBseOccupAnalyzer, this.alightCounts,
						network, this.countsScaleFactor);
				PtBseCountsComparisonAlgorithm ccaOccupancy = new PtBseCountsComparisonAlgorithm(this.ptBseOccupAnalyzer, this.occupCounts,
						network, this.countsScaleFactor);

				String distanceFilterStr = config.findParam("ptCounts", "distanceFilter");
				String distanceFilterCenterNodeId = config.findParam("ptCounts", "distanceFilterCenterNode");
				if ((distanceFilterStr != null) && (distanceFilterCenterNodeId != null)) {
					Double distanceFilter = Double.valueOf(distanceFilterStr);
					ccaBoard.setDistanceFilter(distanceFilter, distanceFilterCenterNodeId);
					ccaAlight.setDistanceFilter(distanceFilter, distanceFilterCenterNodeId);
					ccaOccupancy.setDistanceFilter(distanceFilter, distanceFilterCenterNodeId);
				}

				ccaBoard.setCountsScaleFactor(this.countsScaleFactor);
				ccaAlight.setCountsScaleFactor(this.countsScaleFactor);
				ccaOccupancy.setCountsScaleFactor(this.countsScaleFactor);

				// filter stations here??

				ccaBoard.run();
				ccaAlight.run();
				ccaOccupancy.run();

				String outputFormat = config.findParam("ptCounts", "outputformat");
				if (outputFormat.contains("kml") || outputFormat.contains("all")) {
					ControlerIO ctlIO = controler.getControlerIO();

					String filename = ctlIO.getIterationFilename(iter, "ptBseCountscompare.kmz");
					PtCountSimComparisonKMLWriter kmlWriter = new PtCountSimComparisonKMLWriter(ccaBoard.getComparison(),
							ccaAlight.getComparison(), ccaOccupancy.getComparison(), TransformationFactory.getCoordinateTransformation(config
									.global().getCoordinateSystem(), TransformationFactory.WGS84), this.boardCounts, this.alightCounts,
							this.occupCounts);

					kmlWriter.setIterationNumber(iter);
					kmlWriter.writeFile(filename);
					if (ccaBoard != null) {
						ccaBoard.write(ctlIO.getIterationFilename(iter, "simBseCountCompareBoarding.txt"));
					}
					if (ccaAlight != null) {
						ccaAlight.write(ctlIO.getIterationFilename(iter, "simBseCountCompareAlighting.txt"));
					}
					if (ccaOccupancy != null) {
						ccaOccupancy.write(ctlIO.getIterationFilename(iter, "simBseCountCompareOccupancy.txt"));
					}
				}

				controler.stopwatch.endOperation("compare with pt counts");
			}
		}
	}

	@Override
	public void addStrategyModule(final PlanStrategyModule module) {
		this.delegate.addStrategyModule(module);
	}

	@Override
	public int getNumberOfStrategyModules() {
		return this.delegate.getNumberOfStrategyModules();
	}

	@Override
	public void run(final Person person) {
		this.delegate.run(person);
	}

	@Override
	public void init() {
		this.delegate.init();
	}

	@Override
	public void finish() {
		this.delegate.finish();
	}

	@Override
	public String toString() {
		return this.delegate.toString();
	}

	@Override
	public PlanSelector getPlanSelector() {
		return this.delegate.getPlanSelector();
	}

	/*package*/ class SimResultsContainerImpl implements SimResults<TransitStopFacility> {
		private static final long serialVersionUID = 1L;
		private PtBseOccupancyAnalyzer occupancyAnalyzer = null;

		SimResultsContainerImpl(final PtBseOccupancyAnalyzer oa) {
			this.occupancyAnalyzer = oa;
		}

		@Override
		public double getSimValue(final TransitStopFacility stop, final int startTime_s, final int endTime_s, final TYPE type) { // stopFacility or link
			int hour = startTime_s / 3600;
			Id stopId = stop.getId();
			int[] values = this.occupancyAnalyzer.getOccupancyVolumesForStop(stopId);

			if (values == null) {
				return 0;
			}

			return values[hour] * NewPtBsePlanStrategy.this.countsScaleFactor;
		}

		@Override
		public String toString() {
			final StringBuffer stringBuffer2 = new StringBuffer();
			final String STOPID = "stopId: ";
			final String VALUES = "; values:";
			final char TAB = '\t';
			final char RETURN = '\n';

			for (Id stopId : this.occupancyAnalyzer.getOccupancyStopIds()) { // Only occupancy!
				StringBuffer stringBuffer = new StringBuffer();
				stringBuffer.append(STOPID);
				stringBuffer.append(stopId);
				stringBuffer.append(VALUES);

				boolean hasValues = false; // only prints stops with volumes > 0
				int[] values = this.occupancyAnalyzer.getOccupancyVolumesForStop(stopId);

				for (int ii = 0; ii < values.length; ii++) {
					hasValues = hasValues || (values[ii] > 0);

					stringBuffer.append(TAB);
					stringBuffer.append(values[ii]);
				}
				stringBuffer.append(RETURN);
				if (hasValues)
					stringBuffer2.append(stringBuffer.toString());

			}
			return stringBuffer2.toString();
		}

	}

}