package com.jacamars.dsp.rtb.bidder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;


import java.io.File;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import com.jacamars.dsp.rtb.tools.ChattyErrors;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.jacamars.dsp.crosstalk.budget.AtomicBigDecimal;
import com.jacamars.dsp.crosstalk.budget.Crosstalk;
import com.jacamars.dsp.rtb.blocks.LookingGlass;
import com.jacamars.dsp.rtb.commands.Echo;
import com.jacamars.dsp.rtb.common.Campaign;
import com.jacamars.dsp.rtb.common.Configuration;
import com.jacamars.dsp.rtb.common.SSL;
import com.jacamars.dsp.rtb.fraud.ForensiqClient;

import com.jacamars.dsp.rtb.logtap.WebMQPublisher;

import com.jacamars.dsp.rtb.pojo.*;
import com.jacamars.dsp.rtb.shared.AccountingCache;
import com.jacamars.dsp.rtb.shared.BidCachePool;
import com.jacamars.dsp.rtb.shared.FrequencyGoverner;
import com.jacamars.dsp.rtb.shared.SharedTimer;
import com.jacamars.dsp.rtb.tools.DbTools;
import com.jacamars.dsp.rtb.tools.Env;
import com.jacamars.dsp.rtb.tools.Performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JAVA based RTB2.2 server.<br>
 * This is the RTB Bidder's main class. It is a Jetty based http server that
 * encapsulates the Jetty server. The class is Runnable, with the Jetty server
 * joining in the run method. This allows other parts of the bidder to interact
 * with the server.
 * <p>
 * Prior to calling the RTBServer the configuration file must be Configuration
 * instance needs to be created - which is a singleton. The RTBServer class (as
 * well as many of the other classes also use configuration data in this
 * singleton.
 * <p>
 * A Jetty based Handler class is used to actually process all of the HTTP
 * requests coming into the bidder system.
 * <p>
 * Once the RTBServer.run method is invoked, the Handler is attached to the
 * Jetty server.
 *
 * @author Ben M. Faul
 */
public class RTBServer implements Runnable {

	/**
	 * Logging object
	 */
	protected static final Logger logger = LoggerFactory.getLogger(RTBServer.class);
	/**
	 * The url of the simulator
	 */
	public static final String SIMULATOR_URL = "/xrtb/simulator/exchange";
	/**
	 * The url of where the simulator's resources live
	 */
	public static final String SIMULATOR_ROOT = "web/exchange.html";

	public static final String CAMPAIGN_URL = "/xrtb/simulator/campaign";
	public static final String LOGIN_URL = "/xrtb/simulator/login";
	public static final String ADMIN_URL = "/xrtb/simulator/admin";
	public static final String CAMPAIGN_ROOT = "web/test.html";
	public static final String LOGIN_ROOT = "web/login.html";
	public static final String ADMIN_ROOT = "web/admin.html";

	private static volatile HazelcastInstance hz = null;

	/** Ten years of seconds */
	public static final int TEN_YEARS = 315360000;

	/**
	 * Period for updateing performance stats in redis
	 */
	public static final int PERIODIC_UPDATE_TIME = 60000;
	/**
	 * Pertiod for updating performance stats in Zookeeper
	 */
	public static final int ZOOKEEPER_UPDATE = 15000;

	/**
	 * The strategy to find bids
	 */
	public static int strategy = Configuration.STRATEGY_MAX_CONNECTIONS;;
	/**
	 * The HTTP code for a bid object
	 */
	public static final int BID_CODE = 200; // http code ok
	/**
	 * The HTTP code for a no-bid objeect
	 */
	public static final int NOBID_CODE = 204; // http code no bid

	/**
	 * The percentage of bid requests to consider for bidding
	 */
	public static AtomicLong percentage = new AtomicLong(100); // throttle is
	// wide open at
	// 100, closed
	// at 0

	public static volatile boolean GDPR_MODE = false;

	/**
	 * CIDR blocked counter
	 */
	public volatile static long cidrblocked = 0;

	/**
	 * Indicates of the server is not accepting bids
	 */
	private static volatile boolean stopped = false; // is the server not accepting bid
	private static volatile Object stopFlag = new Object();
	// requests?

	/**
	 * number of threads in the jetty thread pool
	 */
	public static int threads = 1024;

	/**
	 * a counter for the number of requests the bidder has received and processed
	 */
	public static volatile long request = 0;
	/**
	 * Counter for number of bids made
	 */
	public static volatile long bid = 0; // number of bids processed
	/**
	 * Counter for number of nobids made
	 */
	public static volatile long nobid = 0; // number of nobids processed
	/**
	 * Number of errors in accessing the bidder
	 */
	public static volatile long error = 0;
	/**
	 * Number of actual requests
	 */
	public static volatile long handled = 0;
	/**
	 * Number of unknown accesses
	 */
	public static volatile long unknown = 0;
	/**
	 * The configuration of the bidder
	 */
	public static Configuration config;
	/**
	 * The number of win notifications
	 */
	public volatile static long win = 0;
	/**
	 * The number of clicks processed
	 */
	public volatile static long clicks = 0;
	/**
	 * The number of pixels fired
	 */
	public volatile static long pixels = 0;
	/**
	 * The average time
	 */
	public volatile static long avgBidTime;
	public volatile static long avgNoBidTime;

	public volatile static long throttleTime = 0;
	/**
	 * Fraud counter
	 */
	public volatile static long fraud = 0;
	/**
	 * xtime counter
	 */
	public volatile static long xtime = 0;

	/**
	 * video event counter
	 */
	public static volatile long videoevent = 0;

	/** Application callback event */
	public static volatile long postbackevent = 0;
	/**
	 * The adpsend
	 */
	public static volatile double adspend;
	/**
	 * The frequency governer
	 */
	public static volatile FrequencyGoverner frequencyGoverner;

	/**
	 * is the server ready to receive data
	 */
	boolean ready;

	static long deltaTime = 0, deltaWin = 0, deltaClick = 0, deltaPixel = 0, deltaNobid = 0, deltaBid = 0;
	static volatile double qps = 0;
	static double avgx = 0;

	static AtomicLong totalBidTime = new AtomicLong(0);
	static AtomicLong totalNoBidTime = new AtomicLong(0);
	static AtomicLong bidCountWindow = new AtomicLong(0);
	static AtomicLong nobidCountWindow = new AtomicLong(0);
	
	public static volatile List<Map<String,String>> events = new ArrayList<Map<String,String>>();

	/**
	 * The JETTY server used by the bidder
	 */
	public static Server server;

	/**
	 * The Jetty admin handler, if the main jetty handler for the bidder isn't going
	 * to handle it
	 */
	static AdminHandler adminHandler;

	/**
	 * The number of bidders in the bidder farm
	 */
	public static volatile int biddersCount = 1;
	/**
	 * The bidder's main thread for handling the bidder's activities outside of the
	 * JETTY processing
	 */
	Thread me;

	/**
	 * Trips right before the join with jetty
	 */
	CountDownLatch startedLatch = null;

	/**
	 * The campaigns that the bidder is using to make bids with
	 */
	static volatile CampaignSelector campaigns;

	/**
	 * Bid target to exchange class map
	 */
	public static Map<String, BidRequest> exchanges = new HashMap<String, BidRequest>();

	/** Trace stuff coming in to the bidder */
	public static volatile boolean trace = false;

	/**
	 * This is the entry point for the RTB server.
	 *
	 * @param args . String[]. Config file name. If not present, uses default and
	 *             port 8080. Options [-s shardkey] [-p port -x sslport]
	 */
	public static void main(String[] args) {

		String fileName = null;
		String exchanges = null;
		String shard = "";


		if (args.length == 1)
			fileName = args[0];
		else {
			int i = 0;
			while (i < args.length) {
				switch (args[i]) {
				case "-s":
					i++;
					shard = args[i];
					i++;
					break;
				case "-e":
					i++;
					exchanges = args[i];
					i++;
					break;
				default:
					System.out.println("CONFIG FILE: " + args[i]);
					fileName = args[i];
					i++;
					break;
				}
			}
		}

		if (fileName == null) {
			if (Env.GetEnvironment("AWSACCESKEY", null)==null)
				fileName = "payday.json";
			else
				fileName =  "s3://config/payday.json";
			
		}
		
		try {
			new RTBServer(fileName, shard, exchanges);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.exit(0);
		}
	}

	public static HazelcastInstance getSharedInstance() {
		if (hz == null) {
			Config config = new Config();
			
			NetworkConfig networkConfig = config.getNetworkConfig();
            JoinConfig join = networkConfig.getJoin();

            Map hzConfig = Configuration.getInstance().hzConfig;
            if (hzConfig != null) {
                String clusterName = (String) hzConfig.get("clusterName");
                config.setClusterName(clusterName);
                String networkType = (String) hzConfig.get("networkType");
                ArrayList<Map<String,String>> props =  (ArrayList)hzConfig.get("properties");
                if ("kubernetes".equals(networkType)) {
                    join.getMulticastConfig().setEnabled(false);
                    join.getKubernetesConfig().setEnabled(true);
                    Iterator<Map<String,String>> kvIterator = props.iterator();
                    while(kvIterator.hasNext()){
                        Map<String,String> kv = kvIterator.next();
                        String key = kv.get("key");
                        String value = kv.get("value");
                        boolean noLoad = "false".equalsIgnoreCase(kv.get("load"));
                        System.out.println("key=>" + key + " value=>" + value + " noLoad=>" +  noLoad);
                        if(!noLoad) {
                            join.getKubernetesConfig().setProperty(key, value);
                        }
                    }

                }
            }
			
			Echo.registerWithHazelCast(config);
			Campaign.registerWithHazelCast(config);
			AtomicBigDecimal.registerWithHazelCast(config);
			hz = Hazelcast.newHazelcastInstance(config);
			AccountingCache.getInstance(hz);
			BidCachePool.getInstance(hz);

			if (logger != null)
				logger.info("*** Server STARTING, Leader: {} ***", isLeader());
			try {
				System.out.println("*** Server STARTING, Leader: " + isLeader() + " ***");
				//if (Controller.getInstance() != null)						// can happen if this is not a bidder, but is a client.
				//	Controller.getInstance().setMemberStatus();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return hz;
	}

	static volatile Map<String, Long> spurious = new ConcurrentHashMap<String, Long>();

	/**
	 * Track chatty errors so we don't bombard the log with spurious output
	 * 
	 * @param key   String. The key of the error message.
	 * @param value int. The frequency which to track the error (seconds)
	 * @return boolean. Returns true if this is spurious, else false (ok to log).
	 */
	public static boolean spurious(String key, int value) {
		Long v = null;
		if ((v = spurious.get(key)) == null) {
			spurious.put(key, System.currentTimeMillis());
			return false;
		}

		v = System.currentTimeMillis() - v;
		v /= 1000;
		if (v > value) {
			spurious.put(key, System.currentTimeMillis());
			return false;
		}
		return true;
	}

	public static boolean isLeader() {
		
		if (hz== null || hz.getLifecycleService() == null)
			return false;
		
		if (!hz.getLifecycleService().isRunning())
			return false;

		return hz.getCluster().getMembers().iterator().next().localMember();
	}

	/**
	 * Class instantiator of the RTBServer.
	 *
	 * @param fileName String. The filename of the configuration file.
	 * @throws Exception if the Server could not start (network error, error reading
	 *                   configuration)
	 */
	public RTBServer(String fileName) throws Exception {

		Configuration.reset(); // this resquired so that when the server is
		// restarted, the old config won't stick around.
		AddShutdownHook hook = new AddShutdownHook();
		hook.attachShutDownHook();

		Configuration.getInstance(fileName);
		campaigns = CampaignSelector.getInstance(); // used to

		kickStart();
	}

	/**
	 * Class instantiator of the RTBServer.
	 *
	 * @param fileName  String. The filename of the configuration file.
	 * @param shard     String. The shard key of this bidder instance.
	 * @param exchanges String. The list of exchanges to restrict bidding on.
	 * @throws Exception if the Server could not start (network error, error reading
	 *                   configuration)
	 */
	public RTBServer(String fileName, String shard, String exchanges) throws Exception {

		try {
			Configuration.reset(); // this required so that when the server is
			// restarted, the old config won't stick
			// around.

			Configuration.getInstance(fileName, shard, exchanges);

			AddShutdownHook hook = new AddShutdownHook();
			hook.attachShutDownHook();

			// Controller.getInstance();
			campaigns = CampaignSelector.getInstance(); // used to
			kickStart();

		} catch (Exception error) {
			throw new Exception(error);
		}
	}

	private void kickStart() {
		startedLatch = new CountDownLatch(1);
		me = new Thread(this);
		me.start();
	}

	/**
	 * Returns whether the server has started.
	 *
	 * @return boolean. Returns true if ready to start.
	 */
	public boolean isReady() {
		return ready;
	}

	public static void panicStop() {
		try {
			logger.error("PanicStop", "Bidder is shutting down *** NOW ****");
			Thread.sleep(100);
			Controller.getInstance().sendShutdown();

		} catch (Exception e) {
			e.printStackTrace();
		} catch (Error x) {
			x.printStackTrace();
		}

		try {
			hz.getCluster().shutdown();
		} catch (Exception error) {
			logger.warn("Hazelcast cluser already shut down");
		}
	}

	/**
	 * Set summary stats.
	 */
	public static void setSummaryStats() {
		if (xtime == 0)
			avgx = 0;
		else
			avgx = (nobid + bid) / (double) xtime;

		if (System.currentTimeMillis() - deltaTime < 30000)
			return;

		deltaWin = win - deltaWin;
		deltaClick = clicks - deltaClick;
		deltaPixel = pixels - deltaPixel;
		deltaBid = bid - deltaBid;
		deltaNobid = nobid - deltaNobid;

		qps = (deltaWin + deltaClick + deltaPixel + deltaBid + deltaNobid);
		long secs = (System.currentTimeMillis() - deltaTime) / 1000;
		qps = qps / secs;
		deltaTime = System.currentTimeMillis();
		deltaWin = win;
		deltaClick = clicks;
		deltaPixel = pixels;
		deltaBid = bid;
		deltaNobid = nobid;

		// QPS the exchanges
		BidRequest.getExchangeCounts();
	}
	
	public static void stopBidder() {
		synchronized (stopFlag) {
			stopped = true;
		}
	}
	
	public static void startBidder() {
		synchronized (stopFlag) {
			stopped = false;
		}
	}
	
	public static boolean isStopped() {
		synchronized (stopFlag) {
			return stopped;
		}
	}
	
	public static void setState(boolean t) {
		synchronized (stopFlag) {
			stopped = t;
		}
	}

	/**
	 * Retrieve a summary of activity.
	 *
	 * @return String. JSON based stats of server performance.
	 */
	public static String getSummary() throws Exception {
		setSummaryStats();
		Map<String, Object> m = new HashMap<String, Object>();
		m.put("instance", Configuration.getInstance());
		m.put("stopped", stopped);
		m.put("loglevel", Configuration.getInstance().logLevel);
		m.put("n-campaigns", Configuration.getInstance().getCampaignsListReal().size());
		m.put("e-campaigns", Configuration.getInstance().getCampaignsList().size());
		m.put("qps", qps);
		m.put("deltax", avgx);
		m.put("nobidreason", Configuration.getInstance().printNoBidReason);
		m.put("cpu", Performance.getCpuPerfAsString());
		m.put("memUsed", Performance.getMemoryUsed());
		m.put("cores", Performance.getCores());
		m.put("diskFree", Performance.getPercFreeDisk());
		m.put("openfiles", Performance.getOpenFileDescriptorCount());
		m.put("exchanges", BidRequest.getExchangeCounts());
		m.put("lowonthreads", server.getThreadPool().isLowOnThreads());
		m.put("instance", Configuration.instanceName);
		m.put("avgbidtime", avgBidTime);
		m.put("avgnobidtime", avgNoBidTime);

		return DbTools.mapper.writeValueAsString(m);
	}

	/**
	 * Establishes the HTTP Handler, creates the Jetty server and attaches the
	 * handler and then joins the server. This method does not return, but it is
	 * interruptable by calling the halt() method.
	 */
	@Override
	public void run() {

		SSL ssl = Configuration.getInstance().ssl;
		if (Configuration.getInstance().port == 0 && ssl == null) {
			try {
				logger.error("Neither HTTP or HTTPS configured, error, stop");
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return;
		}

		QueuedThreadPool threadPool = new QueuedThreadPool(threads, 50);
		server = new Server(threadPool);
		ServerConnector connector = null;

		if (Configuration.getInstance().port != 0) {
			connector = new ServerConnector(server);
			connector.setPort(Configuration.getInstance().port);
			connector.setIdleTimeout(60000);
		}

		if (Configuration.getInstance().ssl != null) {

			HttpConfiguration https = new HttpConfiguration();
			https.addCustomizer(new SecureRequestCustomizer());
			SslContextFactory sslContextFactory = new SslContextFactory();
			sslContextFactory.setKeyStorePath(ssl.setKeyStorePath);
			sslContextFactory.setKeyStorePassword(ssl.setKeyStorePassword);
			sslContextFactory.setKeyManagerPassword(ssl.setKeyManagerPassword);
			ServerConnector sslConnector = new ServerConnector(server,
					new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(https));
			sslConnector.setPort(Configuration.getInstance().sslPort);

			if (connector != null)
				server.setConnectors(new Connector[] { connector, sslConnector });
			else
				server.setConnectors(new Connector[] { sslConnector });
			try {
				logger.info("SSL configured on port {}", Configuration.getInstance().sslPort);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else
			server.setConnectors(new Connector[] { connector });

		Handler handler = new Handler();

		try {
			BidRequest.compile();
			SessionHandler sh = new SessionHandler(); // org.eclipse.jetty.server.session.SessionHandler
			sh.setHandler(handler);
			server.setHandler(sh); // set session handle

			/**
			 * Override the start state if the deadmanswitch object is not null and the key
			 * doesn't exist
			 */
			

			server.start();

			Thread.sleep(500);

			ready = true;
			deltaTime = System.currentTimeMillis(); // qps timer

			if (Controller.responseQueue != null)
				Controller.responseQueue.add(getStatus());

			logger.info("System start on port: {}", Configuration.getInstance().port);

			startPeridocLogger();

			startedLatch.countDown();

			Runnable admin = () -> {
				try {
					startSeparateAdminServer();
				} catch (Exception error) {
					logger.error("Error: {}", error);
					error.printStackTrace();
					return;
				}
			};
			Thread nthread = new Thread(admin);
			nthread.start();

			SharedTimer.getInstance("timebase",60);

			 
			Thread.sleep(1000);
			int count = BidCachePool.getInstance(getSharedInstance()).getMembersSize();
			
			// Start acconting and crosstalk after hazelcast comes up
			Crosstalk.getInstance();
			
			if (count > 1) {
				if (Configuration.getInstance().deadmanSwitch != null) {
					if (Configuration.getInstance().deadmanSwitch.canRun() == false) {
						RTBServer.stopBidder();
					}
				}
			}
			
			server.join();
		} catch (Exception error) {
			String reason = error.getMessage();
			error.printStackTrace();
			if (error.toString().contains("Interrupt"))

				try {
					logger.error("HALT: : {}", reason);
				} catch (Exception e) {
					e.printStackTrace();
				}
			else
				logger.error("HALT: : {}", reason);
			System.exit(1);
		}
	}

	/**
	 * Start a different handler for control and reporting functions
	 *
	 * @throws Exception if SSL is specified but is not configured
	 */
	void startSeparateAdminServer() throws Exception {
		SSL ssl = Configuration.getInstance().ssl;

		QueuedThreadPool threadPool = new QueuedThreadPool(threads, 50);
		Server server = new Server(threadPool);
		ServerConnector connector;

		if (Configuration.getInstance().adminPort == 0)
			return;

		logger.info("Admin functions are available on port: {}", Configuration.getInstance().adminPort);

		if (!Configuration.getInstance().adminSSL) { // adminPort
			connector = new ServerConnector(server);
			connector.setPort(Configuration.getInstance().adminPort);
			connector.setIdleTimeout(60000);
			server.setConnectors(new Connector[] { connector });
		} else {

			if (config.ssl == null) {
				throw new Exception("Admin port set to SSL but no SSL credentials are configured.");
			}
			logger.info("Admin functions are available by SSL only");
			HttpConfiguration https = new HttpConfiguration();
			https.addCustomizer(new SecureRequestCustomizer());
			SslContextFactory sslContextFactory = new SslContextFactory();
			sslContextFactory.setKeyStorePath(ssl.setKeyStorePath);
			sslContextFactory.setKeyStorePassword(ssl.setKeyStorePassword);
			sslContextFactory.setKeyManagerPassword(ssl.setKeyManagerPassword);
			ServerConnector sslConnector = new ServerConnector(server,
					new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(https));
			sslConnector.setPort(Configuration.getInstance().adminPort);

			server.setConnectors(new Connector[] { sslConnector });
		}

		adminHandler = new AdminHandler();

		SessionHandler sh = new SessionHandler(); // org.eclipse.jetty.server.session.SessionHandler
		sh.setHandler(adminHandler);
		server.setHandler(sh); // set session handle

		server.start();
		server.join();
	}

	/**
	 * Quickie tasks for periodic logging
	 */
	private void startPeridocLogger() throws Exception {

		Runnable statusUpdater;
		statusUpdater = () -> {
			try {
				while (true) {
					Controller.getInstance().setMemberStatus();

					try {
						Controller.getInstance().reportNoBidReasons();
						CampaignProcessor.probe.reset();
					} catch (Exception error) {
						logger.warn("Error in the probe processing: {}",
								error.getMessage());
						error.printStackTrace();
					}

					int count = BidCachePool.getInstance(getSharedInstance()).getMembersSize();
					if (biddersCount != count) {
						logger.info("Recalculating spend rates, old number of bidders: {}, new number: {}",
								biddersCount, count);
						biddersCount = count;
					}

					Thread.sleep(ZOOKEEPER_UPDATE);
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		};
		Thread nthread = new Thread(statusUpdater);
		nthread.start();

		////////////////////

		Runnable task = () -> {
			long count = 0;
			while (true) {
				try {

					long deltaRequests = 0;

					avgBidTime = totalBidTime.get();
					avgNoBidTime = totalNoBidTime.get();
					double davgBidTime = avgBidTime;
					double davgNoBidTime = avgNoBidTime;
					double window = bidCountWindow.get();
					double nowindow = nobidCountWindow.get();
					if (window == 0)
						window = 1;
					if (nowindow == 0)
						nowindow = 1;
					davgBidTime /= window;
					davgNoBidTime /= nowindow;

					String sqps = String.format("%.2f", qps);
					String savgbidtime = String.format("%.2f", davgBidTime);
					String savgnobidtime = String.format("%.2f", davgNoBidTime);

					long a = ForensiqClient.forensiqXtime.get();
					long b = ForensiqClient.forensiqCount.get();

					ForensiqClient.forensiqXtime.set(0);
					ForensiqClient.forensiqCount.set(0);
					totalBidTime.set(0);
					totalNoBidTime.set(0);
					bidCountWindow.set(0);
					nobidCountWindow.set(0);

					server.getThreadPool().isLowOnThreads();
					if (b == 0)
						b = 1;

					String perf = Performance.getCpuPerfAsString();
					int threads = Performance.getThreadCount();
					String pf = Performance.getPercFreeDisk();
					String mem = Performance.getMemoryUsed();
					long of = Performance.getOpenFileDescriptorCount();
					List exchangeCounts = BidRequest.getExchangeCounts();
					int members = BidCachePool.getInstance(getSharedInstance()).getMembersSize();
					String msg = "members: " + members + ", leader: " + RTBServer.isLeader() + ", total-errors=" + RTBServer.error
							+ ", openfiles=" + of + ", cpu=" + perf + "%, mem=" + mem + ", freedsk=" + pf
							+ "%, threads=" + threads + ", low-on-threads= " + server.getThreadPool().isLowOnThreads()
							+ ", qps=" + sqps + ", avgBidTime=" + savgbidtime + "ms, avgNoBidTime=" + savgnobidtime
							+ "ms, total=" + handled + ", requests=" + request + ", bids=" + bid + ", nobids=" + nobid
							+ ", fraud=" + fraud + ", cidrblocked=" + cidrblocked + ", wins=" + win + ", pixels="
							+ pixels + ", clicks=" + clicks + ", exchanges= " + exchangeCounts + ", stopped=" + stopped
							+ ", campaigns=" + Configuration.getInstance().getCampaignsList().size();
					
					// Add crosstalk info if this is the leader
					if (RTBServer.isLeader()) {
						msg = Crosstalk.info + msg;
					}
					
					Map m = new HashMap();
					m.put("timestamp", System.currentTimeMillis());
					m.put("hostname", Configuration.getInstance().instanceName);
					m.put("openfiles", of);
					m.put("cpu", Double.parseDouble(perf));
					m.put("bp", Controller.getInstance().getBackPressure());

					String[] parts = mem.split("M");
					m.put("memused", Double.parseDouble(parts[0]));
					parts[1] = parts[1].substring(1, parts[1].length() - 2);
					parts[1] = parts[1].replaceAll("\\(", "");

					double percmemused = Double.parseDouble(parts[1]);

					m.put("percmemused", percmemused);

					m.put("freedisk", Double.parseDouble(pf));
					m.put("threads", threads);
					m.put("qps", qps);
					m.put("avgbidtime", Double.parseDouble(savgbidtime));
					m.put("avgnobidtime", Double.parseDouble(savgnobidtime));
					m.put("handled", handled);
					m.put("requests", request);
					m.put("nobid", nobid);
					m.put("fraud", fraud);
					m.put("wins", win);
					m.put("pixels", pixels);
					m.put("clicks", clicks);
					m.put("stopped", stopped);
					m.put("bids", bid);
					m.put("total-errors", RTBServer.error);
					m.put("exchanges", exchangeCounts);
					m.put("campaigns", Configuration.getInstance().getCampaignsList().size());

					Controller.getInstance().sendStats(m); // this sends a report to the performance channel

					logger.info("Heartbeat {}", msg);

					if (percmemused >= 94) {
						logger.error("Memory Usage Exceeded, Exiting");
						Controller.getInstance().sendShutdown();
						System.exit(1);
					}

					ExchangeCounts.reset();

					Thread.sleep(PERIODIC_UPDATE_TIME);

					if (count++ == 15) {
						if (request - deltaRequests == 0) {
							logger.info("No requests have been received since the last 15 logging periods");
						}
						deltaRequests = request;
						Configuration.getInstance().sortCampaignsAndCreatives();
						count = 0;
					}

				} catch (Exception e) {
					e.printStackTrace();
					System.exit(1);
				}
			}
		};
		Thread thread = new Thread(task);
		thread.start();

		Runnable throttle = () -> {
			long lastThrottle = 0;
			while (true) {
				throttleTime = totalBidTime.get();
				throttleTime -= lastThrottle;
				double window = bidCountWindow.get();
				if (window == 0)
					window = 1;
				throttleTime /= window;
				lastThrottle = totalBidTime.get();
				try {
					Thread.sleep(5000);
				} catch (Exception error) {

				}
			}

		};
		Thread throttle_t = new Thread(throttle);
		throttle_t.start();
	}

	/**
	 * Returns the server's campaign selector used by the bidder. Generally used by
	 * javascript programs.
	 *
	 * @return CampaignSelector. The campaign selector object used by this server.
	 */
	public CampaignSelector getCampaigns() {
		return campaigns;
	}

	/**
	 * Stop the RTBServer, this will cause an interrupted exception in the run()
	 * method.
	 */
	public void halt() {
		try {
			me.interrupt();
		} catch (Exception error) {
			System.err.println("Interrupt failed.");
		}
		try {
			server.stop();
			while (!server.isStopped())
				Thread.sleep(1);
		} catch (Exception error) {
			error.printStackTrace();
		}
		try {
			logger.error("System shutdown");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Is the Jetty server running and processing HTTP requests?
	 *
	 * @return boolean. Returns true if the server is running, otherwise false if
	 *         null or isn't running
	 */
	public boolean isRunning() {
		if (server == null)
			return false;

		return server.isRunning();
	}

	static volatile Echo myStatus = null;
	/**
	 * Returns the status of this server.
	 *
	 * @return Map. A map representation of this status.
	 */
	public static Echo getStatus() {
		setSummaryStats();
		if (myStatus == null) {
			myStatus = new Echo();
			myStatus.ipaddress = Performance.getInternalAddress();
			myStatus.from = Configuration.getInstance().instanceName;
		}
		myStatus.leader = isLeader();
		myStatus.percentage = percentage.intValue();
		myStatus.stopped = stopped;
		myStatus.request = request;
		myStatus.bid = bid;
		myStatus.win = win;
		myStatus.nobid = nobid;
		myStatus.error = error;
		myStatus.handled = handled;
		myStatus.unknown = unknown;
		myStatus.clicks = clicks;
		myStatus.pixels = pixels;
		myStatus.fraud = fraud;
		myStatus.adspend = adspend;
		myStatus.loglevel = Configuration.getInstance().logLevel;
		myStatus.qps = qps;
		List<Campaign> list = Configuration.getInstance().getCampaignsList();
		myStatus.campaigns.clear();
		for (Campaign c : list) {
			myStatus.campaigns.add(c.name);
		}
		myStatus.avgx.add(avgx);
		myStatus.exchanges = BidRequest.getExchangeCounts();
		myStatus.probe = CampaignProcessor.probe;

		String perf = Performance.getCpuPerfAsString();
		int threads = Performance.getThreadCount();
		String pf = Performance.getPercFreeDisk();
		String mem = Performance.getMemoryUsed();
		myStatus.threads.add(threads);
		myStatus.memory.add(mem);
		myStatus.freeDisk.add(pf);
		myStatus.cpu.add(perf);
		myStatus.cores = Performance.getCores();
		myStatus.ncampaigns = Configuration.getInstance().getCampaignsList().size();
		myStatus.lastupdate = System.currentTimeMillis();
		myStatus.events = RTBServer.events;

		return myStatus;
	}
}

/**
 * JETTY handler for incoming bid request.
 * <p>
 * This HTTP handler processes RTB2.2 bid requests, win notifications, click
 * notifications, and simulated exchange transactions.
 * <p>
 * Based on the target URI contents, several actions could be taken. A bid
 * request can be processed, a file resource read and returned, a click or pixel
 * notification could be processed.
 *
 * @author Ben M. Faul
 */
@MultipartConfig
class Handler extends AbstractHandler {
	/**
	 * The property for temp files.
	 */
	private static final MultipartConfigElement MULTI_PART_CONFIG = new MultipartConfigElement(
			System.getProperty("java.io.tmpdir"));
	private static final Configuration config = Configuration.getInstance();

	static final Logger logger = LoggerFactory.getLogger(Handler.class);
	/**
	 * The randomizer used for determining to bid when percentage is less than 100
	 */
	Random rand = new Random();

	/**
	 * Handle the HTTP request. Basically a list of if statements that encapsulate
	 * the various HTTP requests to be handled. The server makes no distinction
	 * between POST and GET and ignores DELETE>
	 * <p>
	 * >
	 *
	 * @throws IOException      if there is an error reading a resource.
	 * @throws ServletException if the container encounters a servlet problem.
	 */
	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {

		response.addHeader("Access-Control-Allow-Origin", "*");
		response.addHeader("Access-Control-Allow-Headers", "Content-Type");
		response.addHeader("Access-Control-Expose-Headers", "X-TIME");
		response.addHeader("Access-Control-Expose-Headers", "X-REASON");

		InputStream body = request.getInputStream();
		String type = request.getContentType();
		BidRequest br = null;
		;
		String json = "{}";
		RTBServer.handled++;
		int code = RTBServer.BID_CODE;
		baseRequest.setHandled(true);
		long time = System.currentTimeMillis();
		boolean isGzip = false;

		response.setHeader("X-INSTANCE", config.instanceName);

		if (request.getHeader("Content-Encoding") != null && request.getHeader("Content-Encoding").equals("gzip"))
			isGzip = true;

		/*
		 * Uncomment to inspect headers if (1 == 1) { Enumeration headerNames =
		 * request.getHeaderNames(); StringBuilder sb = new
		 * StringBuilder("Header, Target: "); sb.append(target); while
		 * (headerNames.hasMoreElements()) { String headerName = (String)
		 * headerNames.nextElement(); String value = request.getHeader(headerName);
		 * sb.append(headerName); sb.append(": "); sb.append(value); sb.append("\n"); }
		 * try { Controller.getInstance().sendLog(2, "Header Info", sb.toString()); }
		 * catch (Exception e) { } }
		 */

		if (RTBServer.trace)
			logger.info("Trace: {}", target);
		/**
		 * This set of if's handle the bid request transactions.
		 */
		BidRequest x = null;
		try {
			/**
			 * Convert the uri to a bid request object based on the exchange..
			 */

			if (target.contains("/rtb/bids")) {
				BidResponse bresp = null;
				x = RTBServer.exchanges.get(target);

				if (x != null) {

					if (BidRequest.compilerBusy()) {
						baseRequest.setHandled(true);
						response.setHeader("X-REASON", "Server initializing");
						response.setStatus(RTBServer.NOBID_CODE);

						logger.debug("No bid, compiler busy");

						return;
					}

					RTBServer.request++;

					/*************
					 * Uncomment to run smaato compliance testing
					 ****************************************/

					/*
					 * Enumeration<String> params = request.getParameterNames(); String tester =
					 * null; if (params.hasMoreElements()) { smaatoCompliance(target, baseRequest,
					 * request, response,body); return;
					 *
					 * }
					 */

					/************************************************************************************************/

					boolean requestLogged = false;
					// RunRecord log = new RunRecord("bid-request");

					if (isGzip)
						body = new GZIPInputStream(body);

					br = x.copy(body);
					
					// System.out.println(br.toString());
					
					br.incrementRequests();
					if (RTBServer.GDPR_MODE)
						br.enforceGDPR();

					if (!br.enforceMasterCIDR() == false) {
						response.setStatus(br.returnNoBidCode());
						response.setContentType(br.returnContentType());
						baseRequest.setHandled(true);
						RTBServer.cidrblocked++;
						return;
					}

					if (Configuration.getInstance().logLevel == -6) {

						synchronized (Handler.class) {
							dumpRequestInfo(target, request);

							System.out.println(br.getOriginal());
							RTBServer.nobid++;
							Controller.getInstance().sendNobid(new NobidResponse(br.id, br.getExchange()));
							response.setStatus(br.returnNoBidCode());
							response.setContentType(br.returnContentType());
							baseRequest.setHandled(true);
							response.setHeader("X-REASON", "debugging");

							logger.warn("No bid, in debug logic");
							return;
						}
					}

					if (RTBServer.server.getThreadPool().isLowOnThreads()) {
						code = RTBServer.NOBID_CODE;
						json = "Server throttling";
						RTBServer.nobid++;
						response.setStatus(br.returnNoBidCode());
						response.setContentType(br.returnContentType());
						baseRequest.setHandled(true);
						br.writeNoBid(response, time);
						ChattyErrors.printWarningEveryMinute(logger, "Server throttled, low on threads");
						Controller.getInstance().sendRequest(br, false);
						return;
					}

					// Some exchanges like Appnexus send other endpoints, so
					// they are handled here.
					if (br.notABidRequest()) {
						logger.debug("Not a bid request: {}", target);
						code = br.getNonBidReturnCode();
						json = br.getNonBidRespose();
						response.setStatus(code);
						response.getOutputStream().write(json.getBytes());
						RTBServer.request--;
						return;
					}

					if (Configuration.getInstance().getCampaignsList().size() == 0) {
						logger.debug("No campaigns loaded");
						json = br.returnNoBid("No campaigns loaded");
						code = RTBServer.NOBID_CODE;
						RTBServer.nobid++;
						ChattyErrors.printWarningEveryMinute(logger, "Can't bid, no campaigns loaded");
						Controller.getInstance().sendRequest(br, false);
						Controller.getInstance().sendNobid(new NobidResponse(br.id, br.getExchange()));
					} else if (RTBServer.isStopped()) {
						logger.debug("Server stopped");
						json = br.returnNoBid("Server stopped");
						code = RTBServer.NOBID_CODE;
						RTBServer.nobid++;
						ChattyErrors.printWarningEveryMinute(logger, "Can't bid, server stopped");
						Controller.getInstance().sendNobid(new NobidResponse(br.id, br.getExchange()));
					} else if (!checkPercentage()) {
						json = br.returnNoBid("Server throttled");
						logger.debug("Percentage throttled");
						code = RTBServer.NOBID_CODE;
						RTBServer.nobid++;
						ChattyErrors.printWarningEveryMinute(logger, "Can't bid, server throttled");
						Controller.getInstance().sendNobid(new NobidResponse(br.id, br.getExchange()));
					} else {
						bresp = CampaignSelector.getInstance().getMaxConnections(br);
						 if (CampaignSelector.getInstance().getErr() != null) {
							 var reason = CampaignSelector.getInstance().getErr();
							 
							 System.out.println("REASON:"+reason);
							 response.setHeader("X-REASON",reason);
	                     }
						if (bresp == null) {
							code = RTBServer.NOBID_CODE;
							json = br.returnNoBid("No matching campaign");
							RTBServer.nobid++;
							Controller.getInstance().sendRequest(br, false);
							Controller.getInstance().sendNobid(new NobidResponse(br.id, br.getExchange()));
						} else {
							code = RTBServer.BID_CODE;
							if (!bresp.isNoBid()) {
								br.incrementBids();
								Controller.getInstance().sendBid(br, bresp);
								Controller.getInstance().recordBid(bresp);

								if (!requestLogged)
									Controller.getInstance().sendRequest(br, true);

								RTBServer.bid++;

							}
						}
					}
					// log.dump();

					time = System.currentTimeMillis() - time;

					response.setHeader("X-TIME", Long.toString(time));
					RTBServer.xtime += time;

					response.setContentType(br.returnContentType()); // "application/json;charset=utf-8");
					if (code == 204) {
						if (Configuration.getInstance().printNoBidReason)
							System.out.println("No bid: " + json);
						response.setStatus(br.returnNoBidCode());
					}

					baseRequest.setHandled(true);

					if (code == 200) {
						RTBServer.totalBidTime.addAndGet(time);
						RTBServer.bidCountWindow.incrementAndGet();
						response.setStatus(code);
						if (bresp != null)
							bresp.writeTo(response, (isGzip && br.usesGzipResponse));
					} else {
						RTBServer.totalNoBidTime.addAndGet(time);
						RTBServer.nobidCountWindow.incrementAndGet();
						br.writeNoBid(response, time);
					}
					return;
				} else {
					json = "Wrong target: " + target + " is not configured.";
					code = RTBServer.NOBID_CODE;
					RTBServer.logger.warn("Handler error: {}", json);
					RTBServer.error++;
					logger.warn("=============> Wrong target: {} is not configured.", target);
					baseRequest.setHandled(true);
					response.setStatus(code);
					response.setHeader("X-REASON", json);
					response.getWriter().println("{}");
					RTBServer.request--;
					return;
				}
			}

			// //////////////////////////////////////////////////////////////////////
			if (target.contains("/rtb/win")) {

				System.out.println("******************* WIN *****************");
				StringBuffer url = request.getRequestURL();
				String queryString = request.getQueryString();
				response.setStatus(HttpServletResponse.SC_OK);
				json = "";
				if (queryString != null) {
					url.append('?');
					url.append(queryString);
				}
				String requestURL = url.toString();
				try {
					json = WinObject.getJson(requestURL);
					if (json == null) {

					}
					RTBServer.win++;
				} catch (Exception error) {
					response.setHeader("X-ERROR", "Error processing win response");
					response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
					logger.warn("Bad win response {}", requestURL);
					error.printStackTrace();
				}
				response.setContentType("text/html;charset=utf-8");
				baseRequest.setHandled(true);
				response.getWriter().println(json);
				return;
			}

			if (target.contains("/ready")) {
				response.getWriter().println("1");
				return;
			}

			if (target.contains("/callback")) {
				response.setContentType("image/bmp;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);

				String rs = request.getQueryString();
				String rtype = request.getParameter("target");
				if (rtype == null)
					rtype = request.getParameter("type");
				if (rtype == null) {
					logger.warn("Callback problem on: {}", rs);
					logger.warn("Warning, wrong callback message, Missing 'type=xxx' or 'target=xxx' params: {}", rs);
					response.getWriter().println("");
					return;
				}
				boolean debug = false;
				String dbg = request.getParameter("debug");
				if (dbg != null && dbg.equalsIgnoreCase("true"))
					debug = true;

				String cookie = null;
				switch (rtype) {
				case "pixel":
					cookie = GetRtbCookie(debug, request, response, true);
					Controller.getInstance().publishPixel(rs, cookie);
					RTBServer.pixels++;
					break;
				case "postback":
					cookie = GetRtbCookie(debug, request, response, false);
					Controller.getInstance().publishPostbackEvent(rs, cookie);
					RTBServer.postbackevent++;
					break;
				case "delcookie":
					cookie = DeleteRtbCookie(request, response);
					response.setContentType("text/html;charset=utf-8");
					response.getWriter().println(cookie);
					break;
				case "track":
					cookie = GetRtbCookie(debug, request, response, false);
					Controller.getInstance().publishVideoEvent(request, cookie);
					RTBServer.videoevent++;
					break;
				case "redirect":
					cookie = GetRtbCookie(debug, request, response, false);
					String queryString = request.getQueryString();
					String params[] = null;
					if (queryString != null)
						params = queryString.split("url=");
					if (params != null) {
						response.sendRedirect(URLDecoder.decode(params[1], "UTF-8"));
						Controller.getInstance().publishClick(params[0], cookie);
					}
					RTBServer.clicks++;
					return;
					
				case "conversion":
					queryString = request.getQueryString();
					Controller.getInstance().publishConvert(queryString);
					return;

				default:
					logger.warn("Undefined command: {}", rs);
				}
				response.getWriter().println("");
				return;
			}

			if (target.contains("/pixel")) {
				String cookie = GetRtbCookie(false, request, response, true);
				String starget = baseRequest.getOriginalURI();
				Controller.getInstance().publishPixel(starget, cookie);
				response.setContentType("image/bmp;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println("");
				RTBServer.pixels++;
				return;
			}

			if (target.contains("/delpixel")) {
				String pixel = DeleteRtbCookie(request, response);
				response.setContentType("text/html;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(pixel);
				RTBServer.pixels++;
				return;
			}

			/**
			 * Handle install (among other) postbacks
			 */
			if (target.contains("/postback")) {
				String cookie = GetRtbCookie(false, request, response, false);
				Controller.getInstance().publishPostbackEvent(target, cookie);
				response.setContentType("image/bmp;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println("");
				RTBServer.postbackevent++;
				return;
			}

			/**
			 * Handle vide events
			 */
			if (target.contains("/track")) {
				String cookie = GetRtbCookie(false, request, response, false);
				Controller.getInstance().publishVideoEvent(request, cookie);
				response.setContentType("image/bmp;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println("");
				RTBServer.videoevent++;
				return;
			}
			/**
			 * Handle the vast retrieve
			 */
			if (target.contains("/vast")) {
				String vast = Controller.getInstance().getVastVideo(request);
				response.setContentType("text/xml; charset=UTF-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(vast);
				return;
			}

			if (target.contains("/redirect")) {
				String starget = baseRequest.getOriginalURI();
				String cookie = GetRtbCookie(false, request, response, false);

				String queryString = request.getQueryString();
				String params[] = null;
				if (queryString != null) 
					params = queryString.split("url=");

				baseRequest.setHandled(true);
				Controller.getInstance().publishClick(target, cookie);
				
				if (params != null) {
					response.sendRedirect(URLDecoder.decode(params[1], "UTF-8"));
				}
				RTBServer.clicks++;
				return;
			}

			if (target.contains("pinger")) {
				response.setStatus(200);
				response.setContentType("text/html;charset=utf-8");
				baseRequest.setHandled(true);
				response.getWriter().println("OK");
				return;

			}

			if (target.contains("crossdomain.xml")) {
				response.setStatus(200);
				response.setContentType("text/html;charset=utf-8");
				baseRequest.setHandled(true);
				response.getWriter().println("OK");
				return;
			}

			if (target.contains("summary")) {
				response.setContentType("text/javascript;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(RTBServer.getSummary());
				return;
			}

			if (target.contains("favicon")) {
				RTBServer.handled--; // don't count this useless turd.
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println("");
				return;
			}

			if (RTBServer.adminHandler != null) {
				baseRequest.setHandled(true);
				response.setStatus(404);
				logger.warn("Error: wrong request for admin login: {}, target: {}", getIpAddress(request), target);
				RTBServer.error++;
			} else {
				AdminHandler admin = new AdminHandler();
				admin.handle(target, baseRequest, request, response);
				return;
			}
		} catch (Exception error) {
			error.printStackTrace(); // TBD TO SEE THE ERRORS

			/////////////////////////////////////////////////////////////////////////////
			// If it's an aerospike error, see ya!
			//
			if (error.toString().contains("Parse")) {
				if (br != null) {
					br.incrementErrors();
					try {
						logger.error("Error: Bad JSON from {}: {} ", br.getExchange(), error.toString());
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
			////////////////////////////////////////////////////////////////////////////

			RTBServer.error++;
			String exchange = target;
			if (x != null) {
				x.incrementErrors();
				exchange = x.getExchange();
			}
			StringWriter errors = new StringWriter();
			error.printStackTrace(new PrintWriter(errors));
			if (errors.toString().contains("fasterxml")) {
				logger.debug("Handler:handle", "Error: bad JSON data from {}: {}", exchange, error.toString());
			} // else
				// error.printStackTrace();
			response.setStatus(RTBServer.NOBID_CODE);
		}
	}

	/**
	 * Get the RTB cookie on this site. Has the capability to make one if it doesn't
	 * exist.
	 * 
	 * @param debug    boolean. Whether to print cookie info as we process it.
	 * @param request  HttpServletRequest. The request object from the browser.
	 * @param response HttpServletResponse. The response object from the browser.
	 * @param makeNew  boolean. Set to true to drop a cooke on the browser of one
	 *                 does not exist.
	 * @return Cookie. The cookie is returned, or null, if there is no cookie and
	 *         makeNew is false.
	 */
	static String GetRtbCookie(boolean debug, HttpServletRequest request, HttpServletResponse response,
			boolean makeNew) {
		Cookie[] cookies = request.getCookies();

		String domain = request.getServerName().replaceAll(".*\\.(?=.*\\.)", "");

		if (cookies != null) {
			for (int i = 0; i < cookies.length; i++) {
				Cookie c = cookies[i];
				if (debug)
					logger.info("Cookie, name: {}, domain: {}, value: {}", c.getName(), c.getDomain(), c.getValue());
				if (c.getName().equals("rtb4free")) {
					if (debug)
						logger.info("Found cooklie, Returning cookie: {}", c.getValue());
					return c.getValue();
				}
			}
		}
		if (cookies == null) {
			if (!makeNew) {
				if (debug)
					logger.info("Cookie not found, and makenew is false, returning no-cookie");
				return "no-cookie";
			}
		}

		String value = UUID.randomUUID().toString();
		Cookie c = new Cookie("rtb4free", value);
		c.setDomain(domain);

		c.setMaxAge(RTBServer.TEN_YEARS);
		response.addCookie(c);
		if (debug)
			logger.info("No cookie was found, so we make one, returning: {}, with domain: {}", c.getValue(), domain);
		return value;
	}

	/**
	 * Deletes the cookie on the browser. Useful for debugging.
	 * 
	 * @param request  HttpServletRequest. The request object of the browser.
	 * @param response HttpServletResponse. The response object sent back to the
	 *                 browser.
	 * @return String. A message naming the cookie it deleted.
	 */
	static String DeleteRtbCookie(HttpServletRequest request, HttpServletResponse response) {
		Cookie[] cookies = request.getCookies();
		for (int i = 0; i < cookies.length; i++) {
			Cookie c = cookies[i];
			if (c.getName().equals("rtb4free")) {
				c.setMaxAge(0);
				response.addCookie(c);
				return "Deleted RTB cookie: " + c.getValue() + ". Restart your browser to take effect.";
			}
		}
		return "No RTB cookie found";
	}

	void handleJsAndCss(HttpServletResponse response, File file) throws Exception {
		byte fileContent[] = new byte[(int) file.length()];
		FileInputStream fin = new FileInputStream(file);
		int rc = fin.read(fileContent);
		if (rc != fileContent.length)
			throw new Exception("Incomplete read of " + file.getName());
		sendGzipResponse(response, new String(fileContent));
	}

	public static void sendGzipResponse(HttpServletResponse response, String html) throws Exception {

		try {
			byte[] bytes = compressGZip(html);
			response.addHeader("Content-Encoding", "gzip");
			int sz = bytes.length;
			response.setContentLength(sz);
			response.getOutputStream().write(bytes);

		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			response.getOutputStream().println("");
			e.printStackTrace();
		}
	}

	private static String uncompressGzip(InputStream stream) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		GZIPInputStream gzis = new GZIPInputStream(stream);
		byte[] buffer = new byte[1024];
		int len = 0;
		String str = "";

		while ((len = gzis.read(buffer)) > 0) {
			str += new String(buffer, 0, len);
		}

		gzis.close();
		return str;
	}

	protected static byte[] compressGZip(String uncompressed) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		GZIPOutputStream gzos = new GZIPOutputStream(baos);

		byte[] uncompressedBytes = uncompressed.getBytes();

		gzos.write(uncompressedBytes, 0, uncompressedBytes.length);
		gzos.close();

		return baos.toByteArray();
	}

	private void dumpRequestInfo(String target, HttpServletRequest req) {
		int level = Configuration.getInstance().logLevel;
		if (level != -6)
			return;

		Enumeration<String> headerNames = req.getHeaderNames();
		System.out.println("============================");
		System.out.println("Target: " + target);
		System.out.println("IP = " + getIpAddress(req));
		System.out.println("Headers:");
		while (headerNames.hasMoreElements()) {
			String headerName = headerNames.nextElement();
			Enumeration<String> headers = req.getHeaders(headerName);
			System.out.print(headerName + " = ");
			while (headers.hasMoreElements()) {
				String headerValue = headers.nextElement();
				System.out.print(headerValue);
				if (headers.hasMoreElements())
					System.out.print(", ");
			}
			System.out.println();
		}
		System.out.println("----------------------------");
	}

	private void smaatoCompliance(String target, Request baseRequest, HttpServletRequest request,
			HttpServletResponse response, InputStream body) throws Exception {
		String tester = null;
		String json = null;
		BidRequest br = null;

		Enumeration<String> params = request.getParameterNames();
		if (params.hasMoreElements()) {
			String[] dobid = request.getParameterValues(params.nextElement());
			tester = dobid[0];
			System.out.println("=================> SMAATO TEST ====================");
		}

		if (tester == null) {
			System.out.println("              Nothing to Test");
			return;
		}

		if (tester.equals("nobid")) {
			RTBServer.nobid++;
			baseRequest.setHandled(true);
			response.setStatus(RTBServer.NOBID_CODE);
			response.getWriter().println("");
			logger.info("SMAATO NO BID TEST ENDPOINT REACHED");
			Controller.getInstance().sendNobid(new NobidResponse(br.id, br.getExchange()));
			return;
		} else {
			BidRequest x = RTBServer.exchanges.get(target);
			x.setExchange("nexage");
			br = x.copy(body);

			Controller.getInstance().sendRequest(br, false);

			logger.info("SMAATO MANDATORY BID TEST ENDPOINT REACHED");
			BidResponse bresp = null;
			// if (RTBServer.strategy == Configuration.STRATEGY_HEURISTIC)
			// bresp = CampaignSelector.getInstance().getHeuristic(br); // 93%
			// time
			// here
			// else
			bresp = CampaignSelector.getInstance().getMaxConnections(br);
			// log.add("select");
			if (bresp == null) {
				baseRequest.setHandled(true);
				response.setStatus(RTBServer.NOBID_CODE);
				response.getWriter().println("");
				logger.error("Handler:handle", "SMAATO FORCED BID TEST ENDPOINT FAILED");
				Controller.getInstance().sendNobid(new NobidResponse(br.id, br.getExchange()));
				return;
			}
			json = bresp.toString();
			baseRequest.setHandled(true);
			Controller.getInstance().sendBid(br, bresp);
			Controller.getInstance().recordBid(bresp);
			RTBServer.bid++;
			response.setStatus(RTBServer.BID_CODE);

			response.getWriter().println(json);

			System.out.println("+++++++++++++++++++++ SMAATO REQUEST ++++++++++++++++++++++\n\n" +

					br.toString() +

					"\n\n++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");

			System.out.println("===================== SMAATO BID ==========================\n\n" + json
					+ "\n\n==========================================================");

			logger.info("SMAATO FORCED BID TEST ENDPOINT REACHED OK");
			return;
		}
		/************************************************************************************/
	}

	/**
	 * Checks to see if the bidder wants to bid on only a certain percentage of bid
	 * requests coming in - a form of throttling.
	 * <p>
	 * If percentage is set to .20 then twenty percent of the bid requests will be
	 * rejected with a NO-BID return on 20% of all traffic received by the Handler.
	 *
	 * @return boolean. True means try to bid, False means don't bid
	 */
	boolean checkPercentage() {
		if (RTBServer.percentage.intValue() == 100)
			return true;
		int x = rand.nextInt(101);
		return x < RTBServer.percentage.intValue();
	}

	/**
	 * Return the IP address of this
	 *
	 * @param request HttpServletRequest. The web browser's request object.
	 * @return String the ip:remote-port of this browswer's connection.
	 */
	public String getIpAddress(HttpServletRequest request) {
		String ip = request.getHeader("x-forwarded-for");
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("Proxy-Client-IP");
		}
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("WL-Proxy-Client-IP");
		}
		if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		ip += ":" + request.getRemotePort();
		return ip;
	}
}

@MultipartConfig
class AdminHandler extends Handler {
	/**
	 * The property for temp files.
	 */
	private static final MultipartConfigElement MULTI_PART_CONFIG = new MultipartConfigElement(
			System.getProperty("java.io.tmpdir"));
	private static final Configuration config = Configuration.getInstance();

	/**
	 * The randomizer used for determining to bid when percentage is less than 100
	 */
	Random rand = new Random();

	static final Logger logger = LoggerFactory.getLogger(AdminHandler.class);

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {

	//	response.addHeader("Access-Control-Allow-Origin", "*");
	//	response.addHeader("Access-Control-Allow-Headers", "Content-Type");

		InputStream body = request.getInputStream();
		String type = request.getContentType();
		String json = "{}";
		RTBServer.handled++;
		baseRequest.setHandled(true);
		boolean isGzip = false;

		response.setHeader("X-INSTANCE", config.instanceName);

		
		System.out.println("+++++++++++++++>" + target);
		
		try {
			if (request.getHeader("Content-Encoding") != null && request.getHeader("Content-Encoding").equals("gzip"))
				isGzip = true;

			if (RTBServer.trace)
				logger.info("Trace: {}", target);

			if (target.contains("pinger")) {
				response.setStatus(200);
				response.setContentType("text/html;charset=utf-8");
				baseRequest.setHandled(true);
				response.getWriter().println("OK");
				return;

			}

			if (target.contains("info")) {
				response.setContentType("text/javascript;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				Echo e = RTBServer.getStatus();
				String rs = e.toJson();
				response.getWriter().println(rs);
				return;
			}

			if (target.equals("/symbols")) {
				response.setContentType("application/json;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				List<String> names = LookingGlass.getAllSymbolNames();
				Collections.sort(names);
				response.getWriter().println(DbTools.mapper.writeValueAsString(names));
				return;
			}

			if (target.equals("/checkonthis")) {
				response.setContentType("text/html;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				String rs = "<html>" + CampaignProcessor.probe.getTable() + "</html>";
				response.getWriter().println(rs);
				return;
			}

			if (target.equals("/reasons")) {
				response.setContentType("application/json;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(CampaignProcessor.probe.toJson());
				return;
			}

			if (target.equals("/summary")) {
				response.setContentType("text/javascript;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(RTBServer.getSummary());
				return;
			}

			if (target.equals("/status")) {
				baseRequest.setHandled(true);
				response.getWriter().println("OK");
				response.setStatus(200);
				return;
			}

			if (target.contains("favicon")) {
				RTBServer.handled--; // don't count this useless turd.
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println("");
				return;
			}

			if (target.contains(RTBServer.SIMULATOR_URL)) {
				String page = Charset.defaultCharset()
						.decode(ByteBuffer.wrap(Files.readAllBytes(Paths.get(RTBServer.SIMULATOR_ROOT)))).toString();

				response.setContentType("text/html");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				page = SSI.convert(page);
				response.getWriter().println(page);
				return;
			}

			// ///////////////////////////
			if (target.contains(RTBServer.CAMPAIGN_URL)) {
				String page = Charset.defaultCharset()
						.decode(ByteBuffer.wrap(Files.readAllBytes(Paths.get(RTBServer.CAMPAIGN_ROOT)))).toString();

				response.setContentType("text/html");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				page = SSI.convert(page);
				response.getWriter().println(page);
				return;
			}

			if (target.contains(RTBServer.LOGIN_URL)) {
				String page = Charset.defaultCharset()
						.decode(ByteBuffer.wrap(Files.readAllBytes(Paths.get(RTBServer.LOGIN_ROOT)))).toString();

				response.setContentType("text/html");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				page = SSI.convert(page);
				page = Env.substitute(page);
				response.getWriter().println(page);
				return;
			}

			if (target.contains(RTBServer.ADMIN_URL)) {
				String page = Charset.defaultCharset()
						.decode(ByteBuffer.wrap(Files.readAllBytes(Paths.get(RTBServer.ADMIN_ROOT)))).toString();

				page = SSI.convert(page);
				page = Env.substitute(page);
				response.setContentType("text/html");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(page);
				return;
			}
			
			if (target.contains("test.html")) {
				String rs = request.getQueryString();
				System.out.println(rs);
				String [] tuples = rs.split("&");
				String page = Charset.defaultCharset()
						.decode(ByteBuffer.wrap(Files.readAllBytes(Paths.get("www/test.html")))).toString();
				for (String tuple : tuples) {
					String [] p = tuple.split("=");
					page = page.replaceAll(p[0], p[1]);
				}
				System.out.println(page);
				response.setContentType("text/html");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(page);
				return;
			}
			
			////////////////////////////////////////////////////////////////////////////////////////////////////////////////
			
				
				if (target.startsWith("/publish")) {
					response.setStatus(HttpServletResponse.SC_OK);
					response.setContentType("application/json;charset=utf-8");
					baseRequest.setHandled(true);
					String result = null;
					String port = request.getParameter("port");
					String topic = request.getParameter("topic");
					String message = request.getParameter("message");
					if (port == null) {
						response.getWriter().println("{\"error\":\"no port specified\"}");
						return;
					}
					if (topic == null) {
						response.getWriter().println("{\"error\":\"no topic specified\"}");
						return;
					}
					if (message == null) {
						message = getStringFromInputStream(body);
					}
					
					
					try {
						new WebMQPublisher(port,topic,message);
						response.getWriter().println("{\"status\":\"ok\"}");
					} catch (Exception error) {
						response.getWriter().println("{\"error\":\"" + error.toString() + "\"}");
					}
					
					return;
				}

			// /////////////////////////////////////////////////////////////////////////////////////////////////////////////

		} catch (Exception e) {
			logger.warn("Bad html processing on {}: {} at line no: {}" + target, e.toString(),
					Thread.currentThread().getStackTrace()[2].getLineNumber());
			// if (br != null && br.id.equals("123"))
			e.printStackTrace();
			baseRequest.setHandled(true);
			StringBuilder str = new StringBuilder("{ \"error\":\"");
			str.append(e.toString());
			str.append("\", \"file\":\"RTBServer.java\",\"lineno\":");
			str.append(Thread.currentThread().getStackTrace()[2].getLineNumber());
			str.append("}");
			response.setStatus(RTBServer.NOBID_CODE);
			response.getWriter().println(str.toString());
			return;
		}

		if (target.startsWith("/rtb/bids")) {
			response.setStatus(HttpServletResponse.SC_OK);
			baseRequest.setHandled(true);
			response.setStatus(RTBServer.NOBID_CODE);
			return;
		}

		if(target.startsWith("/s3")) {
			baseRequest.setHandled(true);
			outputS3(target,response);
			return;
		}

		/**
		 * This set of if's handle non bid request transactions.
		 *
		 */
		if (target.equals("/control"))
			target = "/control/index.html";
		else
		if (target.equals("/exchange"))
			target = "/exchange/index.html";
		else	
		if (target.equals("/campaigns"))
			target = "/campaigns/index.html";
		
		System.out.println("=============> " + target);
		try {
			type = null;
			if (target.equals("/"))
				target = Configuration.indexPage;

			int x = target.lastIndexOf(".");
			if (x >= 0) {
				type = target.substring(x);
			}
			if (type != null) {
				type = type.toLowerCase().substring(1);
				type = MimeTypes.substitute(type);
				response.setContentType(type);
				File f = new File("./www/" + target);
				if (!f.exists()) {
					f = new File("./web/" + target);
					if (!f.exists()) {
						f = new File(target);
						if (!f.exists()) {
							f = new File("." + target);
							if (!f.exists()) {
								response.setStatus(HttpServletResponse.SC_NOT_FOUND);
								baseRequest.setHandled(true);
								
								System.out.println("======= CANT FIND: ========> " + target);
								return;
							}
						}
					}
				}

				target = f.getAbsolutePath();
				if (!target.endsWith("html")) {
					if (target.endsWith("css") || target.endsWith("js")) {
						response.setStatus(HttpServletResponse.SC_OK);
						baseRequest.setHandled(true);
						handleJsAndCss(response, f);
						return;
					}

					FileInputStream fis = new FileInputStream(f);
					OutputStream out = response.getOutputStream();

					// write to out output stream
					while (true) {
						int bytedata = fis.read();

						if (bytedata == -1) {
							break;
						}

						try {
							out.write(bytedata);
						} catch (Exception error) {
							break; // screw it, pray that it worked....
						}
					}

					// flush and close streams.....
					fis.close();
					try {
						out.close();
					} catch (Exception error) {
						System.err.println(""); // don't care
					}
					return;
				}

			}

			String page = Charset.defaultCharset().decode(ByteBuffer.wrap(Files.readAllBytes(Paths.get(target))))
					.toString();

			page = SSI.convert(page);
			page = Env.substitute(page);
			response.setContentType("text/html");
			response.setStatus(HttpServletResponse.SC_OK);
			baseRequest.setHandled(true);
			sendGzipResponse(response, page);

		} catch (Exception err) {
			if (target.contains("/rtb/appnexus")) {
				response.getWriter().println("OK");
			} else
				System.out.println("-----> Encounted an unexpected target: '" + target
						+ "' in the admin handler, will return code 200");
			response.setStatus(HttpServletResponse.SC_OK);
			baseRequest.setHandled(true);
		}
	}
		
		  public static String getStringFromInputStream(InputStream is) {

				BufferedReader br = null;
				StringBuilder sb = new StringBuilder();

				String line;
				try {

					br = new BufferedReader(new InputStreamReader(is));
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}

				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					if (br != null) {
						try {
							br.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}

				return sb.toString();

			}
		  void outputS3(String target, HttpServletResponse response) {
				String s3 = null;
				response.setContentType(MimeTypes.substitute(target));
				try {
					InputStream is = Configuration.getS3InputStream(target.substring(4));
					OutputStream out = response.getOutputStream();

					// write to out output stream
					while (true) {
						int bytedata = is.read();

						if (bytedata == -1) {
							break;
						}

						try {
							out.write(bytedata);
						} catch (Exception error) {
							break; // screw it, pray that it worked....
						}
					}
					response.setStatus(200);
				} catch (Exception error) {
					s3 = error.getMessage();
					response.setStatus(404);
				}
		  }
}

class AddShutdownHook {
	public void attachShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				RTBServer.panicStop();
			}
		});
		RTBServer.logger.info("*** Shut Down Hook Attached. ***");
	}
}