# Warden stateless handoff and its performance implications

Reviewed 2026-09-07 alongside the [RakNet/NetherNet comparison](../comparison/README.md).

Warden can choose a host and return a self-contained admission ticket to the
client without sending that join to the host. The client's first ICE/STUN request
delivers the ticket. The host validates locally, creates a native WebRTC peer,
then carries game traffic directly. This removes a provider-to-host dependency
from connection establishment. It does not migrate an established connection
between hosts or eliminate transport, replay, routing, or accounting state.

## Revisions and current status

| Evidence | Verified state |
| --- | --- |
| Warden remote `main` | `169dacd4baf9fdd3d7cae6d0e8f5193bf05a7c42`, read from GitHub on the review date. The inspected local source is `a995b3b0efb12443b083b095547abd1335e5f1d6`; the intervening changes affect archive-query redirect handling and tests, not the admission paths discussed below. |
| NetworkCompatible `nxs-dev` | `86b396686039c7da4e80a162d280700ca15af79b`, identical source tree to Warden's recorded Network integration pin `a6e73dfa9061fa7fcd35d61d221441371b5b6ca2`. The three NXS schema/fixture hashes match Warden's `docs/api/nxs-source.json`. |
| Proposed upstream integration | [NetworkCompatible PR 10](https://github.com/teamziax/NetworkCompatible/pull/10) remains open and draft against `upstream`; its conformance checks passed. |
| Public provider discovery | Read `https://agent.warden.cloud/.well-known/nethernet-external-signalling`: advertises `nethernet-external-signalling-v1`, `nxs-admission-v1`, `nxs-es384-v1`, 17 operations and scheduled check-in version 1. This verifies the public discovery response, not a running game-host version or completed gameplay. |
| Warden current-head CI | [Run 34152080132](https://github.com/teamziax/warden-signalling/actions/runs/34152080132) failed one integration timing assertion: local join p95 was 51 ms against a strict `<50 ms` target; 219 integration tests passed, including the nine stateless-admission tests. The cause of that timing miss has not been established. It is not a native or stock-client latency measurement. |

The public discovery response is retained with the comparison artifacts as
`warden-live-discovery-20260907.json`. No Warden deployment or sibling checkout
was changed during this review.

## The connection flow

```mermaid
sequenceDiagram
    participant H as Selected game host
    participant W as Warden
    participant C as Minecraft / NetherNet client
    H->>W: Background registration, activation, profile and health
    W-->>H: Admission keys and lease/check-in schedule
    H->>W: Installed-key acknowledgement
    C->>W: HTTPS join with SDP offer and identity
    W->>W: Authenticate, select ready host, mint ticket, record decision
    W-->>C: SDP answer: host endpoint, fingerprint, ticket ufrag and ICE password
    C->>H: STUN USERNAME = ticket:clientUfrag, MESSAGE-INTEGRITY
    H->>H: Validate ticket, expiry, incarnation and raw STUN integrity
    H->>H: Create native peer; install callbacks; resume retained STUN
    H-->>C: STUN response
    C->>H: DTLS with certificate matching the ticket-bound offer fingerprint
    C->>H: SCTP / NetherNet data channels and game traffic
    H-->>C: Direct game traffic
    H-->>W: Asynchronous ticket and connection outcomes
```

The background exchange publishes a fixed reachable UDP endpoint, a host DTLS
certificate fingerprint, supported SCTP parameters and a random process
incarnation. Registration uses a separate persistent machine signing key. The
admission secret is installed locally and acknowledged before it can be used
for routing and ticket issuance.

For a join, Warden puts AES-GCM encrypted admission claims in the answer's
`a=ice-ufrag`. The `NXS1` token carries expiry, client ICE password, client DTLS
fingerprint, SCTP parameters, network ID and an opaque player-identity hash.
The selected host's secret and fresh incarnation bind its audience; the client
ufrag is also authenticated. Warden derives `a=ice-pwd` separately.

ICE carries the remote ufrag followed by the local ufrag in the first STUN
username, and authenticates that request with the remote ICE password. This
lets an ordinary ICE implementation carry the ticket without understanding
NXS. The password is an integrity key; it is not another transmitted admission
field. See [RFC 8445 section 7.2.2](https://www.rfc-editor.org/rfc/rfc8445.html#section-7.2.2).

The native listener retains the first raw request while Java makes the local
admission decision. It coalesces duplicate pending requests. Raw STUN integrity
is checked before creating a native peer; callbacks are installed before the
retained request is attached and resumed. The later DTLS check proves possession
of the certificate bound into the original offer. STUN integrity alone does not
prove a Minecraft player's identity or successful game login.

The active token defaults to about 30 seconds of validity and is capped at
60 seconds for stateless admission. Expiry gates admission, not the lifetime of
an already connected game session. The host separately bounds pending handshakes,
active sessions and used-ticket claims. Default admission limits are 1,024
sessions, 1,024 pending reservations, 8,192 claims and a 15-second handshake timeout.

Implementation: Warden's
[join handler](https://github.com/teamziax/warden-signalling/blob/169dacd4baf9fdd3d7cae6d0e8f5193bf05a7c42/packages/worker-core/src/routes/signal-join.ts),
[token codec](https://github.com/teamziax/warden-signalling/blob/169dacd4baf9fdd3d7cae6d0e8f5193bf05a7c42/packages/protocol/src/stateless-admission.ts),
and Network's
[native admission channel](https://github.com/teamziax/NetworkCompatible/blob/86b396686039c7da4e80a162d280700ca15af79b/transport-nethernet/src/main/java/dev/kastle/netty/channel/nethernet/admission/NativeAdmissionServerChannel.java),
[admission gate](https://github.com/teamziax/NetworkCompatible/blob/86b396686039c7da4e80a162d280700ca15af79b/transport-nethernet/src/main/java/dev/kastle/netty/channel/nethernet/admission/AdmissionGate.java)
and [provider adapter](https://github.com/teamziax/NetworkCompatible/blob/86b396686039c7da4e80a162d280700ca15af79b/external-signalling/src/main/java/org/cloudburstmc/netty/signalling/admission/NativeProviderTransport.java).

## State, failure and routing behavior

| Situation | Current behavior and consequence |
| --- | --- |
| Warden cannot contact a host for this particular join | No contact is required. Admission uses the ticket and locally installed material. Warden itself must still be available to issue a new answer. |
| Warden storage/archive is unavailable | Stateless handoff does not make the Worker stateless. Authentication, route/key reads and decision recording remain. When the archive binding is present, durable decision publication is awaited before a successful response; failure returns 503. |
| Background provider connection is lost | Direct established sessions are not closed simply because a check-in fails. Warden eventually stops selecting the host when its stored lease/profile deadlines expire. Already issued valid tickets can be admitted locally while the listener and keys remain usable. |
| Process restarts | Persistent registration and certificate identity can survive. Activation advances the provider generation and removes old readiness; the native listener creates a new incarnation. Old-incarnation tickets fail. A fresh profile, key acknowledgement and heartbeat are required for new routing. Existing native connection state is lost on process death. |
| Duplicate first STUN | The native pending request/session handles retransmission. A copied token cannot allocate a second peer from another tuple. Failed native integrity does not permanently consume an otherwise unused valid ticket. |
| Client address changes / failover | Ticket reuse from a different tuple is rejected at admission. Seamless NAT rebinding, ICE restart and cross-host session migration are not established by this implementation or the current benchmark. Recovery should obtain a fresh answer/ticket for the selected live host; client retry behavior still needs acceptance testing. |
| Host drain | Local drain rejects new reservations while existing sessions can finish. The provider drain operation removes routing eligibility. In-flight tickets can race a remote routing change until the host applies its local drain. |
| Suspend or revoke | Warden can exclude a service/credential from new selection independently of a sleeping host. Applying the native `suspend` or `revoke` command closes its listener and sessions. Immediate remote termination is not implied: delivery waits for control polling and connectivity. |
| Key rotation | The host installs and acknowledges the new epoch before new tickets use it. Old ticket keys receive a five-minute retirement deadline in the current lifecycle exchange; each ticket's own shorter expiry still applies. Losing a one-time secret requires fresh provisioning. |
| Ticket issued but client never arrives | Warden has an accepted signalling decision, not a proven connection. Missing outcome data is unknown; it is not proof of rejection or a billable successful gameplay session. |
| Pool membership or routing policy changes | These influence new answer selection. They do not transfer DTLS/SCTP/game state from one host to another. A single public hostname can already select among several registered backends. |

Scheduled check-ins reduce idle control traffic. Current defaults start healthy,
empty hosts at 15 minutes, rise to 30 minutes after an idle hour and 60 minutes
after two; active, unhealthy or unknown-occupancy hosts default to at most
60 seconds. Changes in player count or health can trigger an earlier update.
The provider grants absolute deadlines and retry grace. Control polling follows
the negotiated interval, so a long idle lease also lengthens crash detection and
host-command delivery. These are operational tradeoffs, not free scaling gains.
See the [check-in contract](https://github.com/teamziax/warden-signalling/blob/169dacd4baf9fdd3d7cae6d0e8f5193bf05a7c42/docs/api/provider-check-ins.md)
and [lifecycle implementation](https://github.com/teamziax/warden-signalling/blob/169dacd4baf9fdd3d7cae6d0e8f5193bf05a7c42/packages/worker-core/src/routes/provider-lifecycle.ts).

## Compatibility and evidence limits

The carrier has a material standards limitation. RFC 8839 requires receivers to
accept ICE ufrags up to 256 characters but limits senders to 32. This encoding
uses 167 characters for a 24-character client password and can use the entire
256-character receive allowance. It therefore exceeds the sender limit. The
maximum encodable client password is 91 characters; longer otherwise valid ICE
passwords are rejected without reverting to a per-join host command. See
[RFC 8839 section 5.4](https://www.rfc-editor.org/rfc/rfc8839.html#section-5.4).

The repository's Android binary analysis supports the remote/local username
construction and larger receive limit. It does not prove acceptance through
Minecraft's complete signalling wrapper. Strict sender-limit compliance would
require a different carrier contract, such as a short handle with lookup or an
explicitly supported client extension.

Historical [native integration evidence](https://github.com/teamziax/warden-signalling/blob/169dacd4baf9fdd3d7cae6d0e8f5193bf05a7c42/docs/delivery/nxs-evidence/async-admission/warden-native-admission-evidence.json)
records a 2026-09-06 deterministic Worker/native run: zero per-join host inputs,
zero per-join control commands, both data channels delivered, two correlated
outcomes, rejected forged/replayed packets and restart fencing. Its Warden
working tree had tracked changes, and it explicitly records `stockClient: false`
and `gameplay: false`. Treat it as historical fixture/native evidence, not a
current production acceptance result. The recorded two-host health switch is
new-join routing, not established-session migration.

The current transport benchmark independently exercises the pinned candidate's
real native admitted server and clients. Its local signalling fixture deliberately
excludes the Warden HTTPS service, database/archive and background reporting
client. That evidence applies to established transport performance.

## Wider work: implemented foundation and proposals

Warden currently combines public join services, authenticated backend registration,
freshness/lease gates, installed-key acknowledgement, routing, optional account
claiming and asynchronous connection outcomes. A public service can route among
multiple backends. Backend records currently belong to one service; `pool` and
tags are placement metadata rather than independent reusable pool resources.

Two local design reviews dated 2026-09-07 propose further work. They were untracked
documents in the Warden checkout during this inspection, not changes on the remote
branch or implemented APIs:

- [NXS contract simplification](/home/zulu/development/ziax/warden-signalling/docs/product/nxs-contract-simplification-review-2026-09-07.md): consolidate 17 operations into four ordinary operations plus three maintenance operations. A richer heartbeat would carry profile revisions, installed-key acknowledgements, desired/applied state and readiness. Outcomes would remain prompt and independent of admission, with explicit transport/game/unknown distinctions. This requires a newly negotiated contract; current v1 clients still require all 17 operations.
- [Fleet control-plane proposal](/home/zulu/development/ziax/warden-signalling/docs/product/fleet-control-plane-review-2026-09-07.md): independent backend inventory, reusable pools and identity-preserving consolidation behind one address. The existing default registration creates a service and instance; existing fleet registration creates an instance under an authorized service. Combining existing identities or sharing one backend among independent services needs the proposed model and authority changes.

One current reporting issue should be addressed before interpreting high-volume
join conversion figures. The native channel drains up to 256 events from its
queue, while `ProviderClient.flushEvents()` throws when that returned batch exceeds
100, before persisting it. Because draining removes the events, a burst above
100 can be discarded even without native queue overflow. This is a source-level
finding in the pinned candidate; it has not been reproduced with a live burst
during the throughput campaign. See
[native pollEvents, line 168](https://github.com/teamziax/NetworkCompatible/blob/86b396686039c7da4e80a162d280700ca15af79b/transport-nethernet/src/main/java/dev/kastle/netty/channel/nethernet/admission/NativeAdmissionServerChannel.java#L168)
and [ProviderClient, lines 319 onward](https://github.com/teamziax/NetworkCompatible/blob/86b396686039c7da4e80a162d280700ca15af79b/external-signalling/src/main/java/org/cloudburstmc/netty/signalling/ProviderClient.java#L319).
Admission continues independently; reported connection counts can be incomplete.
The same provider executor performs heartbeat, control and event HTTP work, so
slow reporting can also delay subsequent background work even though it cannot
block the native packet path. The simplification proposal's independent reporting
schedule would need to address this implementation detail.

## How to evaluate performance fairly

Keep these measurements separate:

| Measurement | Required boundaries and evidence |
| --- | --- |
| Established transport | Matched useful bytes, payload sizes, clients, CPU budget, loss and RTT; goodput, latency, delivery, queues/errors, CPU/RSS and recovery. See the [completed RakNet/NetherNet comparison](raknet-nethernet-comparison-20260907.md). |
| Warden answer latency | Client HTTPS request to completed answer; separate identity, route/key reads, token mint and durable decision publication. Record cold/warm behavior and actual geographic RTT. |
| Native admission latency and capacity | First raw STUN to first STUN response, native creation, completed DTLS and both channels open; include concurrent arrivals, replay/invalid floods, pending limits and teardown. Never combine different stage definitions into one latency number. |
| Stock-client connection and gameplay | Actual client offer, normal and boundary token sizes, raw first STUN, DTLS identity, both channels, game login and sustained play. Include realistic NAT/IPv4/IPv6 and retry/failover behavior. |
| Background operations and outcomes | Idle/active request rate, lease-expiry detection, command-application delay, restart fencing, key rollover and correlated delivered outcomes under bursts/outages. |

The expected benefit of stateless handoff is less provider-to-host coordination
and host admission setup delay. The completed transport comparison measures
NetherNet's bandwidth, CPU, latency and loss behavior independently. Neither
the carrier design nor a green native conformance run establishes a general
performance win over the unchanged production-pinned RakNet implementation.
