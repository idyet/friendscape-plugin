package com.friendscape;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
class PanelState
{
	boolean sending;
	/** Null until the local player's name is known. */
	String rsn;
	@Builder.Default
	ConnectionStatus status = ConnectionStatus.LOGGED_OUT;
	/** One per discovered Event; null until discovery answers, empty when on no Roster. */
	List<EventCard> cards;
}
