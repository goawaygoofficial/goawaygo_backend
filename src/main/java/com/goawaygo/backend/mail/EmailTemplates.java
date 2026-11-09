package com.goawaygo.backend.mail;

import java.util.List;

import org.springframework.web.util.HtmlUtils;

import com.goawaygo.backend.common.i18n.Lang;
import com.goawaygo.backend.common.i18n.T;

/**
 * The emails GoAwayGo sends, in the recipient's language (4.7). Every sentence is an English template translated
 * by {@link T}. User-provided values are HTML-escaped.
 */
public final class EmailTemplates {

	private EmailTemplates() {
	}

	public static EmailMessage passwordReset(String to, String name, String link, long validMinutes, Lang lang) {
		return build(to, lang, T.t(lang, "Reset your GoAwayGo password"), name,
				List.of(T.t(lang, "We received a request to reset your GoAwayGo password. Open this link to choose a new one:")),
				T.t(lang, "Reset password"), link,
				List.of(T.t(lang, "The link works once and expires in {0} minutes. If you didn't ask for this, you can ignore this email; your password stays the same.", validMinutes)));
	}

	public static EmailMessage verifyEmail(String to, String name, String link, long validHours, Lang lang) {
		return build(to, lang, T.t(lang, "Verify your email for GoAwayGo"), name,
				List.of(T.t(lang, "Welcome to GoAwayGo! Please confirm your email address by opening this link:")),
				T.t(lang, "Verify email"), link,
				List.of(T.t(lang, "The link expires in {0} hours. If you didn't create a GoAwayGo account, you can ignore this email.", validHours)));
	}

	/** To the new address: confirm it before it becomes the account's email. */
	public static EmailMessage confirmEmailChange(String to, String name, String link, long validHours, Lang lang) {
		return build(to, lang, T.t(lang, "Confirm your new email for GoAwayGo"), name,
				List.of(T.t(lang, "You asked to use this address for your GoAwayGo account. Open this link to confirm it:")),
				T.t(lang, "Confirm email"), link,
				List.of(T.t(lang, "The link expires in {0} hours. Until then your account keeps its old email. If you didn't ask for this, you can ignore this email.", validHours)));
	}

	/** To the old address, after the change: in case it wasn't the owner. */
	public static EmailMessage emailChanged(String to, String name, String newEmail, String helpEmail, Lang lang) {
		return build(to, lang, T.t(lang, "Your GoAwayGo email was changed"), name,
				List.of(T.t(lang, "Your GoAwayGo account now uses {0} instead of this address.", newEmail)), null, null,
				List.of(T.t(lang, "If you didn't do this, write to {0} straight away.", helpEmail)));
	}

	public static EmailMessage passwordChanged(String to, String name, String resetLink, Lang lang) {
		return build(to, lang, T.t(lang, "Your GoAwayGo password was changed"), name,
				List.of(T.t(lang, "The password of your GoAwayGo account was just changed, and every device was signed out."),
						T.t(lang, "If you didn't do this, choose a new password:")),
				T.t(lang, "Reset password"), resetLink, List.of());
	}

	/** After "Delete account": signing in again before {@code date} keeps it. */
	public static EmailMessage deletionScheduled(String to, String name, String date, String signInLink, Lang lang) {
		return build(to, lang, T.t(lang, "Your GoAwayGo account will be deleted on {0}", date), name,
				List.of(T.t(lang, "As you asked, your GoAwayGo account will be deleted on {0}, with your posts, photos, trip plans and everything else in it. It is hidden from others until then.", date),
						T.t(lang, "Changed your mind? Sign in before {0} and your account stays.", date)),
				T.t(lang, "Keep my account"), signInLink, List.of());
	}

	public static EmailMessage accountDeleted(String to, String name, Lang lang) {
		return build(to, lang, T.t(lang, "Your GoAwayGo account was deleted"), name,
				List.of(T.t(lang, "Your GoAwayGo account and everything in it have now been deleted, as you asked 30 days ago."),
						T.t(lang, "Thanks for travelling with us. You're welcome back any time.")),
				null, null, List.of());
	}

	/** To the team: someone reported something in Wanderbook (English: the team reads it). */
	public static EmailMessage wanderbookReport(String to, String what, String reason, String details, String link) {
		String subject = "Wanderbook report: " + what + " (" + reason + ")";
		String text = """
				A %s was reported in Wanderbook.

				Reason: %s
				Details: %s

				Review it: %s
				""".formatted(what, reason, details.isBlank() ? "-" : details, link);
		String html = layout(subject, Lang.EN, """
				<p>A %s was reported in Wanderbook.</p>
				<p><strong>Reason:</strong> %s<br><strong>Details:</strong> %s</p>
				%s
				""".formatted(HtmlUtils.htmlEscape(what), HtmlUtils.htmlEscape(reason),
				details.isBlank() ? "-" : HtmlUtils.htmlEscape(details), button("Review reports", link, Lang.EN)));
		return new EmailMessage(to, subject, text, html);
	}

	/**
	 * "Hi name," then {@code before}, an optional button ({@code label} → {@code link}), {@code after} in grey, and
	 * the sign-off. Plain text and HTML say the same.
	 */
	private static EmailMessage build(String to, Lang lang, String subject, String name, List<String> before,
			String label, String link, List<String> after) {
		String hi = T.t(lang, "Hi {0},", name);
		String signOff = T.t(lang, "- The GoAwayGo team");
		StringBuilder text = new StringBuilder(hi).append("\n\n");
		before.forEach(p -> text.append(p).append("\n\n"));
		if (link != null) {
			text.append(link).append("\n\n");
		}
		after.forEach(p -> text.append(p).append("\n\n"));
		text.append(signOff).append('\n');

		StringBuilder html = new StringBuilder("<p>").append(HtmlUtils.htmlEscape(hi)).append("</p>\n");
		before.forEach(p -> html.append("<p>").append(HtmlUtils.htmlEscape(p)).append("</p>\n"));
		if (link != null) {
			html.append(button(label, link, lang));
		}
		after.forEach(p -> html.append("<p style=\"color:#555\">").append(HtmlUtils.htmlEscape(p)).append("</p>\n"));
		return new EmailMessage(to, subject, text.toString(), layout(subject, lang, html.toString()));
	}

	private static String button(String label, String link, Lang lang) {
		String href = HtmlUtils.htmlEscape(link);
		return """
				<p style="margin:28px 0"><a href="%s" style="background:#f26c32;color:#fff;padding:12px 24px;\
				border-radius:24px;text-decoration:none;font-weight:bold;display:inline-block">%s</a></p>
				<p style="color:#555;font-size:13px">%s<br>\
				<a href="%s" style="color:#004aad;word-break:break-all">%s</a></p>
				""".formatted(href, HtmlUtils.htmlEscape(label),
				HtmlUtils.htmlEscape(T.t(lang, "Or copy this link into your browser:")), href, href);
	}

	/** Arabic reads right to left. */
	private static String layout(String title, Lang lang, String body) {
		String dir = (lang == Lang.AR) ? "rtl" : "ltr";
		return """
				<!doctype html>
				<html lang="%s" dir="%s"><head><meta charset="utf-8"><title>%s</title></head>
				<body style="margin:0;background:#f5f5f5;font-family:Arial,Helvetica,sans-serif;color:#222">
				<div style="max-width:520px;margin:24px auto;background:#fff;border-radius:12px;overflow:hidden">
				<div style="background:#004aad;color:#fff;padding:18px 24px;font-size:20px;font-weight:bold">GoAwayGo</div>
				<div style="padding:8px 24px 24px;line-height:1.5">%s</div>
				</div></body></html>
				""".formatted(lang.code(), dir, HtmlUtils.htmlEscape(title), body);
	}

}
