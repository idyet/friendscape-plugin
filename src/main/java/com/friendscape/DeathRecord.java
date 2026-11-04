package com.friendscape;

/**
 * The Hardcore death record (SPEC 10.2): varp {@link #VARP}, the total level at death, is set on
 * the death tick and cleared by a Reset. Fed the varp once per game tick. Varps sync after login,
 * so the first tick after {@link #reset} is skipped and nothing reads dead or alive until the next.
 */
class DeathRecord
{
	static final int VARP = 6223;
	/** The login tick, then the first tick whose varps can be trusted. */
	private static final int SYNC_TICKS = 2;

	private int ticks;
	private int value;

	/** Records this tick's varp value; true on the death tick, when the record goes from 0 to set. */
	synchronized boolean observe(int varp)
	{
		int before = value;
		boolean wasSynced = synced();
		ticks++;
		if (!synced())
		{
			return false;
		}
		value = varp;
		return wasSynced && before == 0 && varp != 0;
	}

	/** Whether varps have synced since login, so {@link #dead} can be trusted. */
	synchronized boolean synced()
	{
		return ticks >= SYNC_TICKS;
	}

	/** Whether the character is dead, waiting for a Reset or transfer; false until synced. */
	synchronized boolean dead()
	{
		return value != 0;
	}

	/** Forgets the record at logout or a world hop: the next tick is a login tick again. */
	synchronized void reset()
	{
		ticks = 0;
		value = 0;
	}
}
