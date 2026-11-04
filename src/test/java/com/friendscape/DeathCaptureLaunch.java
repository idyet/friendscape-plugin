package com.friendscape;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Spike for 186 check 7: the plugin plus the death capture logger. */
public class DeathCaptureLaunch
{
	// loadBuiltin takes a generic varargs of plugin classes; two classes make javac warn, -Werror fails it
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FriendscapePlugin.class, DeathCapturePlugin.class);
		RuneLite.main(args);
	}
}
