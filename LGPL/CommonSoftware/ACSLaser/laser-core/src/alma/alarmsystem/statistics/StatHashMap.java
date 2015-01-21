/*
ALMA - Atacama Large Millimiter Array
* Copyright (c) European Southern Observatory, 2014 
* 
* This library is free software; you can redistribute it and/or
* modify it under the terms of the GNU Lesser General Public
* License as published by the Free Software Foundation; either
* version 2.1 of the License, or (at your option) any later version.
* 
* This library is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
* Lesser General Public License for more details.
* 
* You should have received a copy of the GNU Lesser General Public
* License along with this library; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
*/
package alma.alarmsystem.statistics;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

/**
 * <code>StatHashMap</code> encapsulate a HashMap where all the alarms
 * received during a time interval are stored.
 * The purpose of this class is to provide more detailed statistics then those
 * published in the log. 
 * <P>
 * <EM>Implementation note</EM><BR>
 * To generate and write statistics on file could be quite consuming.
 * To reduce the impact, when the time interval elapses, {@link #alarmsMap} point to a newly
 * created HashMap and the old one is used by a thread to calculate the statistics.
 * <BR>
 * All the maps are stored in the FIFO {@link StatHashMap#mapsQueue} queue. 
 * The thread waits for a new map in the queue the generate the statistics.
 * The size of the queue is limited to control the memory usage in the unlikely case
 * that generation of statistics lasts longer then a time interval.
 * 
 * @author acaproni
 * @since 2015.2
 */
public class StatHashMap implements Runnable {
	/**
	 * To get more useful statistics, the number of activations
	 * and terminations of each alarm in the last time interval
	 * is stored in this data structure.
	 * 
	 * @author acaproni
	 *
	 */
	private static class AlarmInfo {
		
		/**
		 * The ID of the alarm whose statistics this object holds
		 */
		private final String alarmID;

		/**
		 *  Number of activations in the last time interval
		 */
		private long activations=0;
		
		/**
		 *  Number of terminations in the last time interval
		 */
		private long terminations=0;
		
		/**
		 * Constructor
		 * 
		 * @param alarmID the ID of the alarm
		 */
		public AlarmInfo(String alarmID) {
			if (alarmID==null || alarmID.isEmpty()) {
				throw new IllegalArgumentException("Invalid alarm ID");
			}
			this.alarmID=alarmID;
		}
		
		public void update(boolean active) {
			if (active) {
				activations++;
			} else {
				terminations++;
			}
		}
		
		/**
		 * Getter
		 * 
		 * @return The number of activations
		 */
		public long getActivations() {
			return activations;
		}

		/**
		 * Getter
		 * 
		 * @return The number of terminations
		 */
		public long getTerminations() {
			return terminations;
		}
		
		/**
		 * 
		 * @return The total number of activations and terminations executed on this alarm
		 *         during the time interval
		 */
		public long getTotalOperations() {
			return activations+terminations;
		}
	}

	/**
	 * A {@link AlarmInfo} comparator based on the number of activations 
	 * @author acaproni
	 *
	 */
	private static class ComparatorByActivations implements Comparator<AlarmInfo> {
		@Override
		public int compare(AlarmInfo o1, AlarmInfo o2) {
			Long.valueOf(o1.getActivations()).compareTo(o2.getActivations());
			return 0;
		}
	}
	
	/**
	 * A {@link AlarmInfo} comparator based on the number of operations 
	 * (activations and terminations)
	 *  
	 * @author acaproni
	 *
	 */
	private static class ComparatorByOperations implements Comparator<AlarmInfo> {
		@Override
		public int compare(AlarmInfo o1, AlarmInfo o2) {
			Long.valueOf(o1.getTotalOperations()).compareTo(o2.getTotalOperations());
			return 0;
		}
	}
	
	/**
	 * A {@link AlarmInfo} comparator based on the number of terminations 
	 * @author acaproni
	 *
	 */
	private static class ComparatorByTerminations implements Comparator<AlarmInfo> {
		@Override
		public int compare(AlarmInfo o1, AlarmInfo o2) {
			Long.valueOf(o1.getTerminations()).compareTo(o2.getTerminations());
			return 0;
		}
	}
	
	/**
	 * The logger 
	 */
	private final Logger logger;
	
	/**
	 * {@link #alarmsMap} records the activations/terminations of
	 * each alarm during the current time interval.
	 * It is used later on to calculate the statistics.
	 * <P>
	 * A new map is created for each time interval to minimize the impact in performances
	 * in {@link StatHashMap#processedFS(String, boolean)}. 
	 * <P>
	 * There is no need to synchronize this map because it is modified only
	 * by the synchronized {@link StatHashMap#processedFS(String, boolean)} method.
	 */
	private Map<String, AlarmInfo> alarmsMap = new HashMap<String, AlarmInfo>();
	
	/**
	 * The max number of maps to store in the {@link #mapsQueue}.
	 */
	private static final int MAXQUEUECAPACITY=10;
	
	/**
	 * When the time interval elapses the {@link StatHashMap#alarmsMap} is pushed
	 * into this queue and a new {@link StatHashMap#alarmsMap}.
	 * <P>
	 * The thread gets maps from this queue and generate the statistics.
	 */
	private final BlockingQueue<Map<String,AlarmInfo>> mapsQueue = new ArrayBlockingQueue<Map<String,AlarmInfo>>(MAXQUEUECAPACITY);
	
	/**
	 * The thread to calculate statistics
	 * @see #run()
	 */
	private final Thread thread = new Thread(this,this.getClass().getName());
	
	/**
	 * Statistics are calculated every time interval (in minutes)
	 */
	private final int timeInterval;
	
	/**
	 * Signal if the processing of statistics has been terminated
	 */
	private volatile boolean closed=false;
	
	/**
	 * Constructor
	 * 
	 * @param lgger The logger
	 * @param intervalLength The length of each time interval (in minutes)
	 */
	public StatHashMap(Logger logger, int intervalLength) {
		if (logger==null) {
			throw new IllegalArgumentException("The logger can't be null");
		}
		this.logger=logger;
		if (intervalLength<0) {
			throw new IllegalArgumentException("The time interval must be greater or equal to 0");
		}
		this.timeInterval=intervalLength;
	}
	
	/** 
	 * A new FS has been processed: if not already present,
	 * it is stored in the {@link #alarmsMap} map otherwise its state is updated. 
	 * 
	 * @param alarmID the ID of the FS
	 * @param active The activation state of the FS
	 */
	public void processedFS(String alarmID,boolean active) {
		if (closed || timeInterval==0) {
			return;
		}
		synchronized (this) {
			AlarmInfo info = alarmsMap.get(alarmID);
			if (info==null) {
				info = new AlarmInfo(alarmID);
				alarmsMap.put(alarmID, info);
			}
			info.update(active);	
		}
	}
	
	/**
	 * Calculate the statistics.
	 * <P>
	 */
	public void calcStatistics() {
		if (closed || timeInterval==0) {
			return;
		}
		// Create new map and release the lock so that processedFS does not block
		// impacting alarm server performances
		synchronized (this) {
			if (!mapsQueue.offer(alarmsMap)) {
				// Queue full: log a warning
				logger.warning("Alarm statistics lost for this time interval: the queue is full");
			}
			alarmsMap = new HashMap<String, AlarmInfo>();
		}
	}
	
	/**
	 * The thread picks maps from the {@link #mapsQueue} then calculate and write on file
	 * the statistics.
	 */
	public void run() {
		while (!closed) {
			// Get one map out of the queue
			Map<String, AlarmInfo> map;
			try { 
				map = mapsQueue.take();
			} catch (InterruptedException ie) {
				continue;
			}
			calculate(map);
		}
	}
	
	/**
	 * Start the thread to evaluate the statistics
	 */
	public void start() {
		if (timeInterval!=0) {
			thread.setDaemon(true);
			thread.start();
		}
	}
	
	/**
	 * Stop the thread to calculate the statistics
	 * and free the resources.
	 */
	public void shutdown() {
		closed=false;
		if (timeInterval!=0) {
			thread.interrupt();
		}
	}
	
	/**
	 * Calculate the statistics of the passed time interval.
	 * <P>
	 * The figures calculated are:
	 * <UL>
	 * 	<LI>Total number of different alarms issued
	 * <LI>Total number of activations and terminations
	 * <LI>Total number of terminations
	 * 	<LI>Total number of activations
	 * 	<LI>Average number of activations and terminations per second
	 * 	<LI>The 5 alarms that has been changed (activated and/or terminated) more often
	 * 	<LI>The 5 alarms that has been activated more often
	 * 	<LI>The 5 alarms that has been terminated more often
	 * </UL>
	 * 
	 * @param map The map of alarms
	 */
	private void calculate(Map<String, AlarmInfo> map) {
		// A collection to iterate over the values
		Collection<AlarmInfo> infos = map.values();
		
		// The number of different alarms published in the interval
		int totAlarms = infos.size();
		
		// Total number of activations
		long totActivations=0;
		
		// Total number of terminations
		long totTerminations=0;
		
		// Define three data structures with the alarms ordered by activations, terminations and number of operations
		TreeSet<AlarmInfo> alarmsOrderdByActivations = new TreeSet<StatHashMap.AlarmInfo>(new ComparatorByActivations());
		TreeSet<AlarmInfo> alarmsOrderdByTerminations = new TreeSet<StatHashMap.AlarmInfo>(new ComparatorByTerminations());
		TreeSet<AlarmInfo> alarmsOrderdByOperations= new TreeSet<StatHashMap.AlarmInfo>(new ComparatorByOperations());
		for (AlarmInfo info: infos) {
			totActivations+=info.getActivations();
			totTerminations+=info.getTerminations();
			
			alarmsOrderdByActivations.add(info);
			alarmsOrderdByTerminations.add(info);
			alarmsOrderdByOperations.add(info);
		}
		
		// Total number of operations (i.e. activations and terminations)
		long totOperations=totActivations+totTerminations;
		
		// Average number of operations per second
		double avgOpPerSecond=totOperations/(timeInterval*60);
		
		// Write a new record in the file
	}
}
