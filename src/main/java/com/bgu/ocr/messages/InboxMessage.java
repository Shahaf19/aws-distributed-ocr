package com.bgu.ocr.messages;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Anything a Local App puts on the shared Manager inbox. The JSON carries a {@code type} field
 * ({@code "newTask"} or {@code "terminate"}) so the Manager knows which one it received.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NewTask.class, name = "newTask"),
        @JsonSubTypes.Type(value = Terminate.class, name = "terminate")
})
public sealed interface InboxMessage permits NewTask, Terminate {
}
