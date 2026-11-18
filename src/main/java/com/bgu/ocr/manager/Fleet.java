package com.bgu.ocr.manager;

import java.util.OptionalLong;

/** The EC2 side of a Manager's lifecycle: termination signals, its Workers, itself. A fake in tests. */
public interface Fleet {

    /** Terminate received: tells every Manager (this one included) to shut down, with the message's send time. */
    void markAllManagersTerminating(long terminateSentAt);

    /** Shutting down for being idle: clients must stop counting on this Manager and start a new one if needed. */
    void markSelfTerminating();

    /** The send time of a terminate request addressed to this Manager, if one was made. */
    OptionalLong terminationRequestedAt();

    void terminateMyWorkers();

    /** The last step: on EC2 this ends the process too. */
    void terminateSelf();
}
