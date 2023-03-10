package com.jacamars.dsp.rtb.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import com.sun.management.*;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Class provides basic runtime specifics used in the heartbeat message of the
 * RTB4FREE system.
 * 
 * @author Ben M. Faul
 *
 */
public class Performance {
	/** Megabytes */
	static long mb = 1024 * 1024;
	/** Roundoff format */
	static DecimalFormat formatter = new DecimalFormat("###.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

	/** Set the formatting mode as round down */
	static {
		formatter.setRoundingMode(RoundingMode.DOWN);
	}
	
	public static void main(String []args) throws Exception {
		System.out.println("Cores: " + getCores());
		System.out.println("Memory: " + getMemoryUsed());
	}

	/**
	 * 
	 * Get CPU performance as a String, adjusted by cores
	 * 
	 * @return String. Returns a cpu percentage string
	 */
	public static String getCpuPerfAsString() {
		OperatingSystemMXBean mx = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
		int cores = Runtime.getRuntime().availableProcessors();
		double d = mx.getSystemLoadAverage() * 100 / cores;
        return formatter.format(d);
	}

	/**
	 * Returns the number of cores
	 * 
	 * @return int. Returns the number of cores.
	 */
	public static int getCores() {
		return Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Returns the number of threads.
	 * 
	 * @return int. The number of threads in this process
	 */
	public static int getThreadCount() {
		ThreadMXBean threadMXBean = java.lang.management.ManagementFactory.getThreadMXBean();
		return threadMXBean.getThreadCount();

	}

	/**
	 * Get the percentage of free disk
	 * 
	 * @return String. The string representation of free disk as a percentage of
	 *         the whole.
	 */
	public static String getPercFreeDisk() {

		File file = new File("/");

		long totalSpace = file.getTotalSpace(); // total disk space in bytes.
		long usableSpace = file.getUsableSpace(); /// unallocated / free disk
													/// space in bytes.
		long freeSpace = file.getFreeSpace(); // unallocated / free disk space
												// in bytes.

		double percent = (double) freeSpace / (double) totalSpace * 100;
		return formatter.format(percent);
	}

	/**
	 * Return the amount of virtual memory consumed.
	 * 
	 * @return String. The amount of JVM memory being used (Megabytes (and
	 *         percentange of total)).
	 */
	public static String getMemoryUsed() {
		Runtime runtime = Runtime.getRuntime();
		long memory = runtime.totalMemory() - runtime.freeMemory();
		double perc = memory / (double) runtime.maxMemory() * 100;
		memory /= mb;
		String s = formatter.format(perc);
		return Long.toString(memory) + "M (" + s + "%)";

	}

	/**
	 * Return the number of open files.
	 * 
	 * @return long. The number of open files.
	 */
	@SuppressWarnings("restriction")
	public static long getOpenFileDescriptorCount() {
		OperatingSystemMXBean osStats = ManagementFactory.getOperatingSystemMXBean();

		if (osStats instanceof UnixOperatingSystemMXBean) {
			return ((UnixOperatingSystemMXBean) osStats).getOpenFileDescriptorCount();
		}
		return 0;
	}

	/**
	 * Return a pid with the identified target. Hacky, won't work with multiple instances.
	 * @return int. The pid, or -1 if we can't find it.
	 * @throws Exception on jps failure.
	 */
	public static int getPid(String target) throws Exception {
		Process p = Runtime.getRuntime().exec("jps -l");
		String line = null;
		BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));

		while ((line = in.readLine()) != null) {
			System.out.print("Check: " + line);
			String[] javaProcess = line.split(" ");
			if (javaProcess.length > 1 && javaProcess[1].contains(target)) {
				return Integer.parseInt(javaProcess[0]);
			}
		}
		return -1;
	}
	
	  /**
	    * This method guarantees that garbage collection is
	    * done unlike <code>{@link System#gc()}</code>
	    */
	   public static void gc() {
	     Object obj = new Object();
	     WeakReference ref = new WeakReference<Object>(obj);
	     obj = null;
	     while(ref.get() != null) {
	       System.gc();
	     }
	   }

	/**
	 * Return list of ip4 addresses on this instance.
	 * @return List. The list of ip4 addreses.
	 * @throws Exception if no interfaces are defined
	 */
	public static List<String> getIP4Addresses() throws Exception {
		List<String> addresses = new ArrayList<>();
		for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
			NetworkInterface intf = en.nextElement();
			if (intf.getName().startsWith("lo")==false && intf.isUp()) {
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
					String addr = enumIpAddr.nextElement().getHostAddress();
					if (addr.contains("."))
						addresses.add(addr);
				}
			}

		}
		return addresses;
	}

	/**
	 * Finds the first non loop-back ip address
	 * @return String. The non loop back address found.
	 */
	public static String getInternalAddress()   {
		try {
			List<String> list = getIP4Addresses();
			if (list.size() == 0)
				return "127.0.0.1";
			return list.get(0);
		} catch (Exception error) {

		}
		return "127.0.0.1";
	}

	/**
	 * Return first non loop back address that is up and contains this name.
	 * @param iface String. The interface we are looking for.
	 * @return String. The ipv4 address.
	 * @throws Exception on query interface exceptions.
	 */
	public static String getInternalAddress(String iface) throws Exception {
		List<String> addresses = new ArrayList<>();
		for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
			NetworkInterface intf = en.nextElement();
			if (intf.getName().startsWith("lo")==false && intf.getName().equals(iface)) {
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
					String addr = enumIpAddr.nextElement().getHostAddress();
					if (addr.contains("."))
						return addr;
				}
			}

		}
		return null;
	}
}
