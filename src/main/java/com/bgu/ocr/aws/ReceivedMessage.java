package com.bgu.ocr.aws;

/**
 * One message taken from a queue. It stays hidden from other receivers until it is deleted or its visibility expires.
 *
 * @param receiptHandle  needed to delete the message or extend its visibility
 * @param sentTimestamp  when the queue accepted it (server time, epoch millis); used by the terminate drain logic
 * @param receiveCount   how many times it has been delivered, including this time; used to give up on poison tasks
 */
public record ReceivedMessage(String body, String receiptHandle, long sentTimestamp, int receiveCount) {
}
