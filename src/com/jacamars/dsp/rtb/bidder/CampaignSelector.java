package com.jacamars.dsp.rtb.bidder;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.Random;

import java.util.concurrent.*;

import com.jacamars.dsp.rtb.blocks.WeightedSelector;
import com.jacamars.dsp.rtb.common.*;
import com.jacamars.dsp.rtb.pojo.BidRequest;
import com.jacamars.dsp.rtb.pojo.BidResponse;
import com.jacamars.dsp.rtb.probe.Probe;
import com.jacamars.dsp.rtb.tools.Performance;

import edu.emory.mathcs.backport.java.util.Collections;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class used to select campaigns based on a given bid request. The selector,
 * through the get() request will determine which campaigns/creatives match a
 * bid request. If there is more than one creative found, then one is selected
 * at random, and then the BidRequest object is returned. If no campaign
 * matched, then null is returned.
 *
 * @author Ben M. Faul
 */
public class CampaignSelector {

	static final Logger logger = LoggerFactory.getLogger(CampaignSelector.class);

	static Random randomGenerator = new Random();
	/**
	 * The configuration object used in this selector
	 */
	Configuration config;

	/**
	 * The instance of the singleton
	 */
	static CampaignSelector theInstance;

	// Time high water mark in ms.
	public static volatile int highWaterMark = 100;

	// Executor for handling creative attributes.
	static ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

	StringBuffer err;
	/**
	 * Empty private constructor.
	 */
	private CampaignSelector() {

	}
	
	public String getErr() {
		if (err == null)
			return null;
		return err.toString();
	}

	/**
	 * Returns the singleton instance of the campaign selector.
	 *
	 * @return CampaignSelector. The object that selects campaigns
	 * @throws Exception if there was an error loading the configuration file.
	 */
	public static CampaignSelector getInstance() {
		if (theInstance == null) {
			synchronized (CampaignSelector.class) {
				if (theInstance == null) {
					theInstance = new CampaignSelector();
					theInstance.config = Configuration.getInstance();
				}
			}
		}
		return theInstance;
	}

	/**
	 * Get a bid using the max connections algorithm.
	 * 
	 * @param br BidRequest. The request being considered.
	 * @return BidResponse. The fully formed bid.
	 * @throws Exception on parsing errors.
	 */
	public BidResponse getMaxConnections(BidRequest br) throws Exception {

		boolean xtest = false;
		if (br.id.equals("123")) {
			xtest = true;
			err = new StringBuffer();
		} else
			err = null;

		// Don't proces if there was an error forming the original bid request.
		if (br.notABidRequest())
			return null;

		if (RTBServer.throttleTime > Configuration.getInstance().throttle) {
			if (xtest) {
				logger.info("RTBServer.throttleTime {} > maximum allowed: {}", RTBServer.throttleTime,
						Configuration.getInstance().throttle);
				err.append("The bider is throttled");
			}
			return null;
		}

		if (Configuration.getInstance().getCampaignsList().size() == 0) {
			if (xtest) {
				logger.info("No campaigns are loaded");
				err.append("No campaigns are loaded");
			}
			return null;
		}

		long xtime = System.currentTimeMillis();
		long ztime = System.nanoTime();
		Campaign test = null;
		List<SelectedCreative> select = null;
		int kount = 0;

		List<Campaign> list = Preshuffle.getInstance().getPreShuffledCampaignList();
		
		// System.out.println("======>"+ list.size());

		if (list == null) {
			logger.error("No preshuffled campaigns lists");
			if (err != null)
				err.append("No presuffled campaigns list exists");
			return null;
		}

		///////////////////////////////////////////////////////////////////////////////////////
		List<FrequencyCap> frequencyCap = new ArrayList<FrequencyCap>();
		final List<SelectedCreative> candidates = new ArrayList<SelectedCreative>();
		boolean exchangeIsAdx = br.getExchange().equals("adx");

		int nThreads = Configuration.concurrency;

		ExecutorService executor = Executors.newFixedThreadPool(nThreads);
		int start, stop;
		int howMany = list.size() / nThreads;
		int remainder = list.size() % nThreads;

		if (list.size() < Performance.getCores()) {
			howMany = list.size();
			remainder = 0;
			nThreads = 1;
		}

		List<SelectionWorker> workers = new ArrayList<SelectionWorker>();
		start = 0;
		MutableBoolean flag = new MutableBoolean(false);
		
		CountDownLatch latch = new CountDownLatch(nThreads);
		for (int i = 0; i < nThreads; i++) {
			stop = start + howMany;
			if (i == nThreads - 1)
				stop += remainder;
			SelectionWorker w = new SelectionWorker(start, stop, list, br, exchangeIsAdx, flag, xtest, latch);
			start = stop;
			executor.execute(w);
			workers.add(w);
		}

		executor.shutdownNow();
		if (xtest)
			latch.await(300, TimeUnit.SECONDS);
		else
			latch.await(50, TimeUnit.MILLISECONDS);

		for (int i = 0; i < workers.size(); i++) {
			SelectionWorker w = workers.get(i);
			candidates.addAll(w.candidates);
			frequencyCap = w.frequencyCap;
		}
		
		xtime = System.currentTimeMillis() - xtime;

		if (candidates.size() == 0) {
			if (xtest) {
				logger.info("*** No bid candidates ***");
				workers.forEach(w->{
					err.append(w.getErr());
					err.append("|\n");
				});
			}
			return null;
		}

		BidResponse winner;

		if (!br.multibid) {
			SelectedCreative cr = WeightedSelector.applyAlgorithm(br, candidates);
			winner = br.buildNewBidResponse(cr.getImpression(), cr.getCampaign(), cr.getCreative(), cr.getPrice(),
					cr.getDealId(), (int) xtime);
		} else {
			winner = br.buildNewBidResponse(candidates, (int) xtime);
		}

		if (frequencyCap.size() > 0) {
			winner.frequencyCap = frequencyCap;
		}

		if (Configuration.getInstance().printNoBidReason) {
			for (int i = 0; i < candidates.size(); i++) {
				SelectedCreative cr = candidates.get(i);
				logger.info("Selected winner {}/{}", cr.campaign.name, cr.creative.impid);
			}
		}
		if (RTBServer.frequencyGoverner != null)
			RTBServer.frequencyGoverner.add(winner.camp.name, br);

		if (xtest) {
			workers.forEach(w->{
				err.append(w.getErr());
				err.append("\n");
			});
		}
		
		return winner;
	}

}

/**
 * A class that uses a slice of campaigns to find a match to the bid request.
 */
class SelectionWorker implements Runnable {
	int start = 0;
	int stop = 0;
	List<Campaign> list = new ArrayList<>();
	BidRequest br;
	int count;
	boolean exchangeIsAdx;
	volatile List<SelectedCreative> candidates = new ArrayList<SelectedCreative>();
	static Logger logger = LoggerFactory.getLogger(CampaignSelector.class);
	List<SelectedCreative> select;
	List<FrequencyCap> frequencyCap = new ArrayList<FrequencyCap>();
	Map<String, String> capSpecs = new ConcurrentHashMap<String, String>();
	MutableBoolean flag;
	CountDownLatch latch;
	boolean test;
	StringBuffer err;

	public SelectionWorker(int start, int stop, final List<Campaign> list, final BidRequest br,
			final boolean exchangeIsAdx, MutableBoolean flag, boolean test, CountDownLatch latch) {

		if (test) {
			logger.info("WORKER: {} - {}", start, stop);
			err = new StringBuffer();
		}

		this.test = test;

		this.start = start;
		this.stop = stop;
		for (int i = start; i < stop; i++) {
			this.list.add(list.get(i));
		}
		this.exchangeIsAdx = exchangeIsAdx;
		count = 0;
		this.br = br;
		this.flag = flag;
		this.latch = latch;

	}
	
	public String getErr() {
		if (err == null)
			return null;
		return err.toString();
	}

	public void run() {
		try {
			Campaign test;
			long time = System.currentTimeMillis();

			while (count < list.size() && !flag.booleanValue()) {
				try {
					test = list.get(count);
				} catch (Exception error) {
					logger.info("Campaign was stale, in the selection list");
					break;
				}
				
				test.encodeIfNeeded();

				if (test.isAdx == exchangeIsAdx) {

					if (test.isGoverned(br)) {
						if (Configuration.getInstance().printNoBidReason || this.test)
							logger.info("This campaign is governed: {}, spec: {}", test.name, br.synthkey);
						try {
							CampaignProcessor.probe.process(br.getExchange(), test.name, Probe.GLOBAL,
									Probe.FREQUENCY_GOVERNED);
						} catch (Exception e) {
							e.printStackTrace();
						}
					} else {
						CampaignProcessor p = new CampaignProcessor(test, br, null, null);
						p.run();

						select = p.getSelectedCreative();
						if (select != null && select.size() != 0) {
							if (test.isCapped(br, capSpecs)) {
								if (Configuration.getInstance().printNoBidReason || this.test)
									logger.info("This campaign is capped: {}, spec: {}", test.name,
											capSpecs.get(test.name));
								try {
									CampaignProcessor.probe.process(br.getExchange(), test.name, Probe.GLOBAL,
											Probe.FREQUENCY_CAPPED);
								} catch (Exception error) {
									error.printStackTrace();
								}
							} else {
								if (test.frequencyCap != null) {
									FrequencyCap f = test.frequencyCap.copy();
									f.capKey = capSpecs.get(test.name);
									frequencyCap.add(f);
								}

								for (int ii = 0; ii < select.size(); ii++) {
									candidates.add(select.get(ii));
								}
								if (!(br.multibid || test.weights == null)) {
									flag.setValue(true);
									break;
								}
							}
						}
						if (err != null)
							err.append(p.getErr());
					}

					if (!this.test && (System.currentTimeMillis() - time) > 50) {
						if (this.test)
							logger.info("WARNING, Worker: {}, stopped at: {} of {}", start, count, list.size());
						return;
					}
				}

				count++;
			}
		} finally {
			latch.countDown();
		}
	}

}
