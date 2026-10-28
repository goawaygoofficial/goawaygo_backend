package com.goawaygo.backend.mail;

import org.springframework.web.util.HtmlUtils;

/** The emails GoAwayGo sends. User-provided values are HTML-escaped. */
public final class EmailTemplates {

	private EmailTemplates() {
	}

	public static EmailMessage passwordReset(String to, String name, String link, long validMinutes) {
		String subject = "Reset your GoAwayGo password";
		String text = """
				Hi %s,

				We received a request to reset your GoAwayGo password. Open this link to choose a new one:

				%s

				The link works once and expires in %d minutes. If you didn't ask for this, you can ignore this email;
				your password stays the same.

				- The GoAwayGo team
				""".formatted(name, link, validMinutes);
		String html = layout(subject, """
				<p>Hi %s,</p>
				<p>We received a request to reset your GoAwayGo password.</p>
				%s
				<p style="color:#555">The link works once and expires in %d minutes. If you didn't ask for this, you can
				ignore this email; your password stays the same.</p>
				""".formatted(HtmlUtils.htmlEscape(name), button("Reset password", link), validMinutes));
		return new EmailMessage(to, subject, text, html);
	}

	public static EmailMessage verifyEmail(String to, String name, String link, long validHours) {
		String subject = "Verify your email for GoAwayGo";
		String text = """
				Hi %s,

				Welcome to GoAwayGo! Please confirm your email address by opening this link:

				%s

				The link expires in %d hours. If you didn't create a GoAwayGo account, you can ignore this email.

				- The GoAwayGo team
				""".formatted(name, link, validHours);
		String html = layout(subject, """
				<p>Hi %s,</p>
				<p>Welcome to GoAwayGo! Please confirm your email address.</p>
				%s
				<p style="color:#555">The link expires in %d hours. If you didn't create a GoAwayGo account, you can
				ignore this email.</p>
				""".formatted(HtmlUtils.htmlEscape(name), button("Verify email", link), validHours));
		return new EmailMessage(to, subject, text, html);
	}

	/** To the team: someone reported something in Wanderbook. */
	public static EmailMessage wanderbookReport(String to, String what, String reason, String details, String link) {
		String subject = "Wanderbook report: " + what + " (" + reason + ")";
		String text = """
				A %s was reported in Wanderbook.

				Reason: %s
				Details: %s

				Review it: %s
				""".formatted(what, reason, details.isBlank() ? "-" : details, link);
		String html = layout(subject, """
				<p>A %s was reported in Wanderbook.</p>
				<p><strong>Reason:</strong> %s<br><strong>Details:</strong> %s</p>
				%s
				""".formatted(HtmlUtils.htmlEscape(what), HtmlUtils.htmlEscape(reason),
				details.isBlank() ? "-" : HtmlUtils.htmlEscape(details), button("Review reports", link)));
		return new EmailMessage(to, subject, text, html);
	}

	private static String button(String label, String link) {
		String href = HtmlUtils.htmlEscape(link);
		return """
				<p style="margin:28px 0"><a href="%s" style="background:#f26c32;color:#fff;padding:12px 24px;\
				border-radius:24px;text-decoration:none;font-weight:bold;display:inline-block">%s</a></p>
				<p style="color:#555;font-size:13px">Or copy this link into your browser:<br>\
				<a href="%s" style="color:#004aad;word-break:break-all">%s</a></p>
				""".formatted(href, label, href, href);
	}

	private static String layout(String title, String body) {
		return """
				<!doctype html>
				<html><head><meta charset="utf-8"><title>%s</title></head>
				<body style="margin:0;background:#f5f5f5;font-family:Arial,Helvetica,sans-serif;color:#222">
				<div style="max-width:520px;margin:24px auto;background:#fff;border-radius:12px;overflow:hidden">
				<div style="background:#004aad;color:#fff;padding:18px 24px;font-size:20px;font-weight:bold">GoAwayGo</div>
				<div style="padding:8px 24px 24px;line-height:1.5">%s</div>
				</div></body></html>
				""".formatted(HtmlUtils.htmlEscape(title), body);
	}

}
