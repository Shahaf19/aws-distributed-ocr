package com.bgu.ocr.messages;

/** Local App → shared Manager inbox: shut the whole system down (see DECISIONS.md, "Terminate shuts down all Managers"). */
public record Terminate() implements InboxMessage {
}
