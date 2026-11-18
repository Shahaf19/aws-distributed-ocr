package com.bgu.ocr.manager;

import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.InboxMessage;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.MalformedMessageException;
import com.bgu.ocr.messages.NewTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Reads the inbox for a shutting-down Manager instead of accepting jobs (DECISIONS.md, "Terminate shuts down all
 * Managers"). A NewTask sent before the terminate gets a "rejected" reply (the spec: "creates response messages for
 * the jobs, if needed"). One sent after it comes from a client that will start a new Manager: it is held, then
 * released at the end for that Manager. Extra terminate messages and garbage are deleted.
 */
public final class InboxDrainer {

    private final MessageQueue inbox;
    private final long rejectSentBefore;
    private final BiConsumer<NewTask, ReceivedMessage> reject;
    private final List<ReceivedMessage> held = new ArrayList<>();

    /**
     * @param rejectSentBefore the terminate message's SentTimestamp; jobs sent at or before it are rejected.
     *                         {@code Long.MIN_VALUE} (idle shutdown) rejects nothing and holds everything.
     * @param reject           replies "rejected" to the job's client and deletes its NewTask
     */
    public InboxDrainer(MessageQueue inbox, long rejectSentBefore, BiConsumer<NewTask, ReceivedMessage> reject) {
        this.inbox = inbox;
        this.rejectSentBefore = rejectSentBefore;
        this.reject = reject;
    }

    /** One long poll; returns how many messages it got (0 when the inbox has nothing visible left). */
    public int drainOnce() {
        List<ReceivedMessage> messages = inbox.receive();
        for (ReceivedMessage message : messages) {
            InboxMessage parsed;
            try {
                parsed = Json.fromJson(message.body(), InboxMessage.class);
            } catch (MalformedMessageException e) {
                inbox.delete(message);
                continue;
            }
            if (!(parsed instanceof NewTask task)) {
                inbox.delete(message);   // another terminate: we're already shutting down
            } else if (message.sentTimestamp() <= rejectSentBefore) {
                reject.accept(task, message);
            } else {
                held.add(message);   // hidden from other Managers until released (or its visibility runs out)
            }
        }
        return messages.size();
    }

    /** Makes the held NewTasks visible again right away, for a new Manager. */
    public void releaseHeld() {
        for (ReceivedMessage message : held) {
            try {
                inbox.extendVisibility(message, 0);
            } catch (RuntimeException e) {
                System.err.println("Could not release a held job (it reappears after its visibility timeout): " + e);
            }
        }
        held.clear();
    }
}
