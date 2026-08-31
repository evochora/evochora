package org.evochora.datapipeline.api.resources.storage;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A write that becomes visible only when it is declared finished.
 * <p>
 * A plain {@link OutputStream} has one way to end and cannot tell apart the two that matter here:
 * a writer that is done, and a writer that gave up halfway. Closing means both, so a stream that
 * publishes on close publishes whatever it has — including half a Parquet file, which passes a
 * size check and then breaks every read over the directory it lies in.
 * <p>
 * Here the two are separate. {@link #publish()} says the content is complete; closing without it
 * discards the write. That matches how a caller already writes:
 * <pre>
 * try (PublishedOutputStream out = storage.openAnalyticsOutputStream(...)) {
 *     in.transferTo(out);
 *     out.publish();
 * }
 * </pre>
 * A failure anywhere in the block leaves it through the exception, {@code publish} is never
 * reached, and nothing appears under the destination name.
 * <p>
 * <strong>Implementations.</strong> A filesystem writes into a temporary file beside the
 * destination and moves it there on publication; closing without publishing removes it. Object
 * storage has no rename, and the two signals are its own: a multipart upload becomes an object
 * only with {@code CompleteMultipartUpload}, so that is the publication, and closing without it
 * must send {@code AbortMultipartUpload} - the parts are billed until it does, and an upload left
 * open is charged for indefinitely. Either way there is no moment at which half a file carries the
 * destination name.
 */
public abstract class PublishedOutputStream extends OutputStream {

    /**
     * Declares the written content complete, so that closing makes it visible.
     * <p>
     * Calling it more than once is allowed and does nothing after the first time.
     *
     * @throws IOException If the content cannot be readied for publication
     */
    public abstract void publish() throws IOException;
}
