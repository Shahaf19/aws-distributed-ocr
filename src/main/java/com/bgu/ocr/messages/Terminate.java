package com.bgu.ocr.messages;

/** Local App → shared Manager inbox: shut the whole system down (every Manager, its Workers and its queues). */
public record Terminate() implements InboxMessage {
}
