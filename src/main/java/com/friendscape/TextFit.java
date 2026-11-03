package com.friendscape;

import java.awt.FontMetrics;

/** Fits a line to a width by cutting only its name, so what comes before and after always shows. */
final class TextFit
{
	static final String ELLIPSIS = "...";

	private TextFit()
	{
	}

	/** {@code head + name + tail}, the name cut and ended with "..." when the whole is wider than {@code width}. */
	static String fit(String head, String name, String tail, FontMetrics metrics, int width)
	{
		if (metrics.stringWidth(head + name + tail) <= width)
		{
			return head + name + tail;
		}
		int keep = name.length();
		while (keep > 0 && metrics.stringWidth(head + name.substring(0, keep) + ELLIPSIS + tail) > width)
		{
			keep--;
		}
		return head + name.substring(0, keep).trim() + ELLIPSIS + tail;
	}
}
