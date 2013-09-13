/*
 * ALMA - Atacama Large Millimiter Array (c) European Southern Observatory, 2007
 * 
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * 
 */
package alma.acsplugins.alarmsystem.gui.table;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import alma.acs.gui.util.threadsupport.EDTExecutor;

/**
 * A container for the alarms as needed by the AlarmTableModel
 * <P>
 * It is composed of 2 collections:
 * <OL>
 *  <LI> the <code>HashMap</code> stores each entry accessed by its alarmID (the key)
 *  <LI> the <code>Vector</code> of <code>Strings</code> used to remember the position of 
 *  						each alarm when the max number of alarms has been reached
 *                          It also allows to access the alarms by row
 * </OL>         
 * Basically the array stores the alarmID of each row in the table.
 * The content of the row i.e. the Alarm, is then obtained by the
 * HashMap passing the alarmID.
 * <BR>
 * In this way it is possible to get the entry of a row by getting
 * the key from the Vector. And it is possible to get an alarm
 * from the HashMap.
 * <P>
 * Synchronization is done by thread confinement (inside the EDT).
 * Invocation of read only methods must be done inside the EDT.
 * 
 * @author acaproni
 *
 */
public class AlarmsContainer {
	
	/**
	 * The exception generated by the Alarm Container 
	 * 
	 * @author acaproni
	 *
	 */
	public class AlarmContainerException extends Exception {

		/**
		 * Constructor
		 * 
		 * @see java.lang.Exception
		 */
		public AlarmContainerException() {
			super();
		}

		/**
		 * Constructor
		 * 
		 * @see java.lang.Exception
		 */
		public AlarmContainerException(String message, Throwable cause) {
			super(message, cause);
		}

		/**
		 * Constructor
		 * 
		 * @see java.lang.Exception
		 */
		public AlarmContainerException(String message) {
			super(message);
		}

		/**
		 * Constructor
		 * 
		 * @see java.lang.Exception
		 */
		public AlarmContainerException(Throwable cause) {
			super(cause);
		}
		
	}

	/**
	 * The entries in the table
	 */
	private Map<String, AlarmTableEntry> entries = Collections.synchronizedMap(new HashMap<String, AlarmTableEntry>());
	
	/**
	 * The index when the reduction rules are not applied
	 * <P>
	 * Each item in the vector represents the ID of the entry 
	 * shown in a table row when the reduction rules are not used.
	 */
	private final List<String> index = Collections.synchronizedList(new Vector<String>());
	
	/**
	 * The maximum number of alarms to store in the container
	 */
	private final int maxAlarms;
	
	/**
	 * Build an AlarmContainer 
	 * 
	 * @param max The max number of alarms to store in the container
	 * @param panel The <code>AlarmPanel</code>
	 */
	protected AlarmsContainer(int max) {
		maxAlarms=max;
	}
	
	/**
	 * Return the number of alarms in the container.
	 * 
	 * @return The number of alarms in the container
	 */
	public int size() {
		return index.size();
	}
	
	/**
	 * Add an entry (i.e a alarm) in the collection.
	 * <P>
	 * If there is no room available in the container,
	 * an exception is thrown.
	 * Checking if there is enough room must be done by the
	 * caller.
	 * 
	 * @param alarm The not null entry to add
	 * @throw {@link AlarmContainerException} If the entry is already in the container
	 */
	protected void add(final AlarmTableEntry entry) throws AlarmContainerException {
		if (entry==null) {
			throw new IllegalArgumentException("The entry can't be null");
		}
		if (entry.getAlarmId()==null ||entry.getAlarmId().length()==0) {
			throw new IllegalStateException("The alarm ID is invalid");
		}
		if (index.size()>=maxAlarms) {
			throw new ArrayIndexOutOfBoundsException("Container full");
		}
		if (entries.containsKey(entry.getAlarmId())) {
			throw new AlarmContainerException("Alarm already in the Container");
		}
		if (index.contains(entry.getAlarmId())) {
			// entries contains the key but index not!!!!
			throw new IllegalStateException("Inconsistency between index and entries");
		}
		try {
			EDTExecutor.instance().executeSync(new Runnable() {
				@Override
				public void run() {
					index.add(entry.getAlarmId());
					entries.put(entry.getAlarmId(), entry);
				}
			});
		} catch (Throwable t) {
			throw new AlarmContainerException("Error adding alarm",t);
		}
	}

	/**
	 * Check if an alarm with the given ID is in the container
	 * 
	 * @param alarmID The ID of the alarm
	 * @return true if an entries for an alarm with the given ID exists
	 *              in the container
	 */
	public boolean contains(String alarmID) {
		if (alarmID==null) {
			throw new IllegalArgumentException("The ID can't be null");
		}
		return entries.containsKey(alarmID);
	}
	
	/**
	 * Return the entry in the given position
	 * 
	 * @param pos The position of the alarm in the container
	 * @param reduced <code>true</code> if the alarms in the table are reduced
	 * @return The AlarmTableEntry in the given position
	 */
	public AlarmTableEntry get(final int pos) {
		if (pos<0  || pos>index.size()) {
			throw new IndexOutOfBoundsException("Can't acces item at pos "+pos+": [0,"+index.size()+"]");
		}
		
		String ID = index.get(pos);
		AlarmTableEntry entry=get(ID);
		
		
		if (entry==null) {
			throw new IllegalStateException("Inconsistent state of AlarmsContainer: entry is null at pos "+pos);
		}
		return entry;
	}
	
	/**
	 * Return the entry with the given ID
	 * 
	 * @param id The not null ID of the alarm in the container
	 * @return The AlarmTableEntry with the given position
	 *         <code>null</code>if the container does not contain an entry for the given id
	 */
	public AlarmTableEntry get(String id) {
		if (id==null) {
			throw new IllegalArgumentException("The ID can't be null");
		}
		return entries.get(id);
	}
	
	/**
	 * Remove all the elements in the container
	 */
	protected void clear() {
		EDTExecutor.instance().execute(new Runnable() {
			@Override
			public void run() {
				index.clear();
				entries.clear();
			}
		});		
	}
	
	/**
	 * Remove the oldest entry in the container
	 * 
	 * @return The removed item
	 * @throws AlarmContainerException If the container is empty
	 */
	protected AlarmTableEntry removeOldest() throws AlarmContainerException {
		if (index.size()==0) {
			throw new AlarmContainerException("The container is empty");
		}
		
		class OldRemover implements Runnable {

			// The entry removed
			private AlarmTableEntry removedEntry;
			
			public AlarmTableEntry getRemovedEntry() {
				return removedEntry;
			}
			
			public void run() {
				String ID = index.remove(0);
				if (ID==null) {
					throw new IllegalStateException("The index vector returned a null item");
				}
				removedEntry = entries.remove(ID);
			}
			
		}
		
		OldRemover remover = new OldRemover();
		try {
			EDTExecutor.instance().executeSync(remover);
		} catch (Throwable t) {
			throw new IllegalStateException("Error removing the oldest entry",t);
		}
		
		AlarmTableEntry ret = remover.getRemovedEntry();
		if (ret==null) {
			throw new IllegalStateException("The entries  HashMap contains a null entry");
		}
		return ret;
	}
	
	/**
	 * Remove the entry for the passed alarm
	 * 
	 * @param entry The alarm whose entry must be removed
	 * @throws AlarmContainerException If the alarm is not in the container
	 */
	protected void remove(final AlarmTableEntry entry) throws AlarmContainerException {
		if (entry==null) {
			throw new IllegalArgumentException("The alarm can't be null");
		}
		try {
			EDTExecutor.instance().executeSync(new Runnable() {
				
				@Override
				public void run() {
					String ID=entry.getAlarmId();
					int pos=index.indexOf(ID);
					if (pos<0) {
						IllegalStateException iste =new IllegalStateException("Alarm not in the container");
						throw iste;
					}
					index.remove(pos);
					AlarmTableEntry oldEntry = entries.remove(ID);
					if (oldEntry==null) {
						throw new IllegalStateException("The ID was in index but not in entries");
					}
				}
			});
		} catch (Throwable t) {
			throw new AlarmContainerException("Error removing alarm",t);
		}
	}
	
	/**
	 * Remove all the inactive alarms of a given type.
	 * <P>
	 * If the type is INACTIVE all inactive alarms are deleted
	 * regardless of their priority
	 * 
	 * @param type The type of the inactive alarms
	 * @return The number of alarms removed
	 */
	protected int removeInactiveAlarms(final AlarmGUIType type) throws AlarmContainerException {
		if (type==null) {
			throw new IllegalArgumentException("The type can't be null");
		}
		
		/**
		 * A class to remove the alarm of the given type inside the EDT
		 * @author acaproni
		 *
		 */
		class InactiveAlarmsRemover implements Runnable {
			/**
			 * The number of alarms removed
			 */
			private int removed=0;
			
			/**
			 * 
			 * @return The number of removed alarms
			 */
			public int getRemoved() {
				return removed;
			}

			@Override
			public void run() {
				Vector<String> keys = new Vector<String>();
				for (String key: entries.keySet()) {
					keys.add(new String(key));
				}
				for (String key: keys) {
					AlarmTableEntry alarm = entries.get(key);
					if (alarm==null) {
						throw new IllegalStateException("Got a null alarm for key "+key);
					}
					if (alarm.getStatus().isActive()) {
						continue;
					}
					if (type==AlarmGUIType.INACTIVE || alarm.getPriority()==type.id) {
						// Remove the alarm
						try {
							remove(alarm);
							removed++;
						} catch (Throwable t) {
							throw new IllegalStateException("Exception got removing "+alarm.getAlarmId()+" from the AlarmsContainer",t);
						}
					}
				}
				
			}
			
		}
		
		InactiveAlarmsRemover remover = new InactiveAlarmsRemover();
		try {
			EDTExecutor.instance().executeSync(remover);
		} catch (Throwable t) {
			throw new AlarmContainerException("Error removing inactive alarms of type"+type,t);
		}
		return remover.getRemoved();
	}
	
	/**
	 * Replace the alarm in a row with passed one.
	 * <P>
	 * The entry to replace the alarm is given by the alarm ID of the parameter.
	 * 
	 * @param newAlarm The not null new alarm 
	 * @throws AlarmContainerException if the entry is not in the container
	 */
	protected void replace(final AlarmTableEntry newAlarm) throws AlarmContainerException {
		if (newAlarm==null) {
			throw new IllegalArgumentException("The alarm can't be null");
		}
		try {
			EDTExecutor.instance().executeSync(new Runnable() {
				
				@Override
				public void run() {
					int pos = index.indexOf(newAlarm.getAlarmId());
					if (pos<0) {
						throw new IllegalStateException("Entry not present in the container");
					}
					AlarmTableEntry entry = entries.get(newAlarm.getAlarmId());
					if (entry==null) {
						// There was no entry for this ID
						throw new IllegalStateException("Inconsistent state of index and entries");
					}
					entry.updateAlarm(newAlarm);
					// If active, move the item in the head of the container
					if (newAlarm.getStatus().isActive()) {
						String key = index.remove(pos);
						index.add(0,key);
					}
				}
			});
		} catch (Throwable t) {
			throw new AlarmContainerException("Error replacing entry "+newAlarm,t);
		} 	
		
	}
	
	/**
	 * Check if the container has alarm not yet acknowledged.
	 * <P>
	 * If there are alarms to be acknowledged by the user, this
	 * method returns the highest of their priorities.
	 * Note that for alarm system the highest priority 
	 * is 0 and lowest is 3.
	 * 
	 * @return  -1 if there are not alarm to acknowledge;
	 * 			the highest priority of the alarm to acknowledge
	 * @throws AlarmContainerException in case of error getting the highest priority to ack
	 */
	protected int hasNotAckAlarms() throws AlarmContainerException {
		
		class AckAlarmsChecker implements Runnable {
			
			int higestPriorityToAck=Integer.MAX_VALUE;

			/**
			 * 
			 * @return The highest priority to ack 
			 *         or -1 if there are not alarm to ack
			 */
			public int getHigestPriorityToAck() {
				return (higestPriorityToAck==Integer.MAX_VALUE)?-1:higestPriorityToAck;
			}

			@Override
			public void run() {
				higestPriorityToAck=Integer.MAX_VALUE;
				Set<String> keys=entries.keySet();
				for (String key: keys) {
					AlarmTableEntry entry=entries.get(key);
					if (entry.getStatus().isActive() && entry.isNew() && entry.getPriority()<higestPriorityToAck) {
						higestPriorityToAck=entry.getPriority();
					}
					if (higestPriorityToAck==0) {
						break;
					}
				}
				
			}
			
		}
		
		AckAlarmsChecker checker = new AckAlarmsChecker();
		try {
			EDTExecutor.instance().executeSync(checker);
		} catch (Throwable t) {
			throw new AlarmContainerException("Error getting the alarm highest priority of the alarms to ACK",t);
		}
		
		return checker.getHigestPriorityToAck();
	}
}