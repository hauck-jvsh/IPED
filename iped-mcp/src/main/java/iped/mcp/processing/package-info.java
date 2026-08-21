/**
 * Case creation: turning an evidence source into a processed case the rest of the server can open.
 *
 * <h2>The engine never runs inside this process</h2>
 *
 * <p>
 * Processing is invoked by executing the installation's {@code iped.jar} as an external process.
 * That is not a preference — it is forced by the build graph. {@code Bootstrap} and
 * {@code iped.app.processing.Main} live in {@code iped-app}, and {@code iped-app} <b>depends on</b>
 * {@code iped-mcp}; the reverse dependency would be circular and the build refuses it. Anyone
 * reaching for {@code Manager} or {@code IPEDConfig} here to "just run it in-process" will discover
 * this as a compile error, which is why it is written down first.
 *
 * <p>
 * Once discovered, the constraint turns out to be the right design. An external process hands over,
 * with no extra mechanism: an operating-system process handle to cancel with, an exit code to derive
 * the outcome from, memory isolation that keeps this server light while the engine takes the
 * machine, and the death-with-the-server that the job lifecycle requires. It is also the pattern
 * IPED already uses in three places — {@code SleuthkitClient}, {@code ParsingProcess} and
 * {@code Bootstrap} itself.
 *
 * <h2>Three invariants that are easy to break</h2>
 *
 * <ul>
 * <li><b>Cancelling destroys the process tree, not the child.</b> {@code Bootstrap} assembles the
 * classpath and spawns <i>another</i> JVM; the grandchild is what reads evidence. Killing only the
 * child leaves the engine reading evidence and writing to the destination after the server has
 * declared the job over — an evidence-integrity defect, not a latency one.</li>
 *
 * <li><b>Nothing captured from the child ever reaches this process's standard output.</b> The child
 * stream is a pipe this server owns; standard output here is the protocol channel, and a single log
 * line written to it corrupts the session.</li>
 *
 * <li><b>{@code AuditRecord} gains no field.</b> {@code AuditTrail.verify} recomposes the hashed
 * node from what it reads, so one extra field invalidates verification of trails already issued.
 * Job state lives in {@link iped.mcp.processing.JobStore}; the session-to-job link lives in the
 * session manifest.</li>
 * </ul>
 *
 * <h2>Progress has two sources, not one</h2>
 *
 * <p>
 * Counters (processed/total, percent, rate) are read by <i>numeric shape</i>, which survives a
 * locale change. The current <i>phase</i> is only ever emitted as localized prose with no numeric
 * anchor, so the child's locale is declared explicitly rather than inherited — pinning it is what
 * makes the phase readable at all, not a secondary safeguard. The phase is also the only thing that
 * answers "is it alive?" during discovery, commit and optimization, which emit no numbers and can
 * last minutes on a large case.
 */
package iped.mcp.processing;
