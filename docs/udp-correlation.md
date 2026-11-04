# UDP operation correlation and recovery

Each logical operation has a free-running `correlation_id` (uint32, starting at
1), scoped by the app's random `client_session` and persistent client identity.
The session rotates on process start or counter wrap. Each UDP exchange still
has an independent message ID (`id` / `reply_to` on the wire). Retries preserve
the exact original bytes and both identifiers. No extension negotiation is used;
update the gateway and phone together.

One button action retains its correlation through preflight, vehicle command,
result polling and diagnostics. The pending receipt stores this context so app
restart can query the original operation. `command.get` uses empty arguments and
the original correlation. A reply is matched by message ID, GW identity and
correlation; vehicle receipts additionally match the session, boot and revision.
GW returns the canonical original command message ID even if another message ID
was used for a duplicate operation.

Transport budgets are 5 s for reads and 6 s for write acknowledgement, with sends
at 0/700/1800/3500 ms. A single lost read retains the socket and cached data.
Optional read failure skips other optional reads that iteration. Offline begins
after 15 s without a valid reply. Genuine reconnect preserves snapshots for the
same gateway boot; changed boot clears them.

Lost write acknowledgement/transport interruption keeps the original action
pending and polls its result, without issuing another vehicle command or
restarting the 35-second watch. A late `result_unknown` does not immediately fail
a still-pending action within that window. Semantic rejections and final timeout
still produce the existing error popup. Unsent actions are discarded on hard
connection failure. Preflight accepts multi-second RTT, while the GW admission
deadline stays 5 s ahead of the returned GW timestamp.

Tests use loopback sockets and the in-process demo, never a live vehicle. They
cover unchanged retries, 3.1-second latency, wrong-correlation replies, socket
reuse after timeout, canonical receipt lookup and counter/session wrap. The
Python simulator also echoes and deduplicates the correlation extension.
