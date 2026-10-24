package com.goawaygo.backend.mail;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Queues emails so they are sent <em>after</em> the surrounding transaction commits: an email never goes out for work
 * that was rolled back (e.g. a sign-up that failed).
 *
 * <p>By default ({@code goawaygo.mail.async=false}) the email is sent right after the commit, still inside the request,
 * because Cloud Run only gives the backend CPU (and bills) while a request is being handled. Forgot-password pads
 * its response to a fixed minimum time, so sending inside the request doesn't reveal whether the account exists.
 * {@code goawaygo.mail.async=true} sends from a background thread instead, for hosts that keep CPU after the response.
 *
 * <p>Delivery failures are logged (and reported to Sentry), never thrown: the user's action already succeeded, and
 * reset/verification links can be requested again. A persistent outbox with retries is the upgrade path if more
 * critical mail is added.
 */
@Component
public class EmailOutbox implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(EmailOutbox.class);

	private final ApplicationEventPublisher events;

	private final EmailSender sender;

	private final Executor executor;

	public EmailOutbox(ApplicationEventPublisher events, EmailSender sender, MailProperties properties) {
		this.events = events;
		this.sender = sender;
		this.executor = properties.async() ? backgroundExecutor() : new SyncTaskExecutor();
	}

	/** Sends {@code message} once the current transaction commits (immediately if there is none). */
	public void queue(EmailMessage message) {
		this.events.publishEvent(new QueuedEmail(message));
	}

	@TransactionalEventListener(fallbackExecution = true)
	void onCommitted(QueuedEmail queued) {
		this.executor.execute(() -> {
			try {
				this.sender.send(queued.message());
			}
			catch (RuntimeException ex) {
				// Never log the message body: it contains a one-time link.
				log.error("Failed to send email '{}': {}", queued.message().subject(), ex.getMessage());
			}
		});
	}

	/** Lets queued emails finish (up to 10 s) when the application shuts down. */
	@Override
	public void destroy() {
		if (this.executor instanceof ThreadPoolTaskExecutor pool) {
			pool.shutdown();
		}
	}

	private static Executor backgroundExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("email-");
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(500);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		executor.initialize();
		return executor;
	}

	record QueuedEmail(EmailMessage message) {
	}

}
