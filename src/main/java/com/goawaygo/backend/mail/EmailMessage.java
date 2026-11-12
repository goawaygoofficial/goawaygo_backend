package com.goawaygo.backend.mail;

/** One email: a plain-text body for every client plus an HTML body for clients that show it. */
public record EmailMessage(String to, String subject, String text, String html) {
}
