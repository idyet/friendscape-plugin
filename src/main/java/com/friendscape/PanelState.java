package com.friendscape;

import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
class PanelState
{
	boolean sending;
	/** Null until the local player's name is known. */
	String rsn;
	@Builder.Default
	ConnectionStatus status = ConnectionStatus.LOGGED_OUT;
	/** One per discovered Event; null until discovery answers, empty when on no Roster. */
	List<EventCard> cards;
	/** Events whose mini board the player closed (SPEC 10.3). */
	@Builder.Default
	Set<Integer> closedBoards = Set.of();
	/** Events left out of the standings overlay (SPEC 10.3). */
	@Builder.Default
	Set<Integer> hiddenStandings = Set.of();
	/** Events whose Codeword overlay the player hid (SPEC 10.3). */
	@Builder.Default
	Set<Integer> hiddenCodewords = Set.of();
	/** Cards under a header per Group instead of one list (SPEC 10.3). */
	boolean byGroup;
	/** Group numbers whose header the player collapsed, {@link CardGroups#OTHER_KEY} for "Other events". */
	@Builder.Default
	Set<Integer> collapsedGroups = Set.of();
}
